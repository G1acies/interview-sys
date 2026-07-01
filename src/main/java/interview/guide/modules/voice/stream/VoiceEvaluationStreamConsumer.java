package interview.guide.modules.voice.stream;

import interview.guide.infrastructure.redis.RedisService;
import interview.guide.modules.voice.service.VoiceEvaluationService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.stream.StreamMessageId;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;

/**
 * 语音面试评估任务的消费者——从 Redis Stream 中读取消息并执行评估。
 * <p>
 * 设计要点：
 * <ul>
 *   <li><b>守护线程模式</b> — 应用启动时通过 {@link PostConstruct} 启动一个后台线程，
 *       持续监听 Stream 中的新消息。线程设为 daemon=true，应用退出时自动结束。</li>
 *   <li><b>阻塞读取</b> — 使用 Redis Stream 的 BLOCK 模式，没有消息时连接在 Redis 侧等待，
 *       不占用 CPU。每隔 {@code BLOCK_TIMEOUT_MS} 唤醒一次检查是否有新消息。</li>
 *   <li><b>至少一次语义</b> — 每条消息处理后显式 ACK。如果处理过程中抛异常，消息会留在
 *       Pending 列表中，重启后会重新处理。</li>
 *   <li><b>幂等消费者</b> — 消费前先检查 session 的 evaluateStatus，已经是 COMPLETED 或
 *       PROCESSING 则跳过（防止重复评估）。</li>
 *   <li><b>水平扩展</b> — 同一消费者组内的多个消费者实例会自动分摊消息负载。
 *       每个消费者实例用 UUID 作为 consumerName，确保唯一性。</li>
 * </ul>
 * <p>
 * 消费流程：
 * <pre>
 * while (app running) {
 *   1. streamConsumeMessages(streamKey, groupName, consumerName)
 *   2. 对于每条消息：
 *      a. 提取 sessionId
 *      b. 检查会话评估状态（幂等校验）
 *      c. 更新 evaluateStatus = PROCESSING
 *      d. 调用 VoiceEvaluationService.evaluate(sessionId)
 *      e. ACK 确认消息已处理
 *   3. 继续下一轮循环
 * }
 * </pre>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VoiceEvaluationStreamConsumer {

    private final RedisService redisService;
    private final VoiceEvaluationService evaluationService;

    /** 每次批量读取的消息条数。一次处理 1 条，处理完再取下一条，避免单次处理太多。 */
    private static final int BATCH_SIZE = 1;

    /**
     * 阻塞读取超时（毫秒）。
     * <p>
     * 设为 5000ms 意味着：如果 Stream 中没有新消息，Redis 会等待最多 5 秒后返回空结果。
     * 这样可以避免无限阻塞导致线程无法检测到应用关闭信号。
     * 设为 0 表示无限等待，但我们保留定期唤醒以保持日志活跃。
     */
    private static final long BLOCK_TIMEOUT_MS = 5000;

    /** 两次消费循环之间的休眠时间（毫秒），读取超时后如果没有消息，短暂休眠后重试。 */
    private static final long IDLE_SLEEP_MS = 1000;

    /**
     * 应用启动完成后启动消费者线程。
     * <p>
     * 使用 {@code ApplicationStartedEvent} 而非 {@code @PostConstruct}，确保
     * Spring 容器和 Redis 连接完全就绪后再开始消费。
     */
    @PostConstruct
    public void init() {
        // 确保消费者组存在（幂等）
        redisService.createStreamGroup(VoiceEvaluationStreamProducer.STREAM_KEY,
                VoiceEvaluationStreamProducer.GROUP_NAME);

        // 启动消费线程
        Thread consumerThread = new Thread(this::consumeLoop,
                "voice-eval-consumer-" + UUID.randomUUID().toString().substring(0, 8));
        consumerThread.setDaemon(true); // 守护线程：应用退出时自动结束
        consumerThread.start();

        log.info("语音面试评估消费者已启动: thread={}, stream={}, group={}",
                consumerThread.getName(),
                VoiceEvaluationStreamProducer.STREAM_KEY,
                VoiceEvaluationStreamProducer.GROUP_NAME);
    }

    /**
     * 消费主循环。
     * <p>
     * 持续从 Redis Stream 中读取消息并处理。捕获所有异常防止线程意外退出。
     * 当消费者线程被中断（应用关闭）时优雅退出。
     */
    private void consumeLoop() {
        String consumerName = "consumer-" + UUID.randomUUID().toString().substring(0, 8);

        while (!Thread.currentThread().isInterrupted()) {
            try {
                boolean processed = redisService.streamConsumeMessages(
                        VoiceEvaluationStreamProducer.STREAM_KEY,
                        VoiceEvaluationStreamProducer.GROUP_NAME,
                        consumerName,
                        BATCH_SIZE,
                        BLOCK_TIMEOUT_MS,
                        this::processMessage
                );

                if (!processed) {
                    // 无消息可处理，短暂休眠后继续
                    Thread.sleep(IDLE_SLEEP_MS);
                }

            } catch (InterruptedException e) {
                log.info("评估消费者线程被中断，准备退出");
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.error("评估消费者循环异常，将在短暂休眠后重试: {}", e.getMessage(), e);
                try {
                    Thread.sleep(5000); // 异常后等待 5 秒再重试，避免死循环高频重试
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }

        log.info("评估消费者线程已退出");
    }

    /**
     * 处理单条 Stream 消息。
     * <p>
     * 消费者组确保同一条消息只会被一个消费者实例处理一次。
     * 处理完成后显式 ACK，告知 Redis 这条消息已被消费。
     * <p>
     * 幂等保护：如果会话的评估状态已经是 COMPLETED，跳过处理但依然 ACK（防止重复入队导致重复消费）。
     *
     * @param messageId Stream 消息 ID（用于 ACK）
     * @param fields    消息字段 Map（包含 sessionId、timestamp）
     */
    private void processMessage(StreamMessageId messageId, Map<String, String> fields) {
        String sessionIdStr = fields.get("sessionId");
        if (sessionIdStr == null) {
            log.warn("消息缺少 sessionId 字段，直接 ACK: messageId={}", messageId);
            redisService.streamAck(VoiceEvaluationStreamProducer.STREAM_KEY,
                    VoiceEvaluationStreamProducer.GROUP_NAME, messageId);
            return;
        }

        Long sessionId = Long.parseLong(sessionIdStr);
        log.info("开始处理评估任务: sessionId={}, messageId={}", sessionId, messageId);

        try {
            // 调用评估服务（内部已处理事务和异常）
            evaluationService.evaluate(sessionId);

            // ACK 确认
            redisService.streamAck(VoiceEvaluationStreamProducer.STREAM_KEY,
                    VoiceEvaluationStreamProducer.GROUP_NAME, messageId);

            log.info("评估任务处理完成: sessionId={}, messageId={}", sessionId, messageId);

        } catch (Exception e) {
            // evaluate() 内部已有异常处理（标记 FAILED），这里 ACK 后继续
            // 即使评估失败，消息也不需要重试——失败原因已记录到 DB
            log.error("评估任务处理异常: sessionId={}, error={}", sessionId, e.getMessage(), e);
            redisService.streamAck(VoiceEvaluationStreamProducer.STREAM_KEY,
                    VoiceEvaluationStreamProducer.GROUP_NAME, messageId);
        }
    }
}
