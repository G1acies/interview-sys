package interview.guide.modules.voice.stream;

import interview.guide.infrastructure.redis.RedisService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;

/**
 * 语音面试评估任务的生产者——通过 Redis Stream 发送异步评估消息。
 * <p>
 * 为什么用 Redis Stream 而不是直接 @Async：
 * <ul>
 *   <li><b>可靠性</b> — Stream 消息持久化在 Redis 中，服务重启后未处理的消息不会丢失</li>
 *   <li><b>至少一次语义</b> — Consumer 处理完后显式 ACK，确保每条消息都被处理</li>
 *   <li><b>水平扩展</b> — 多个消费者实例属于同一消费者组时，消息自动负载均衡</li>
 *   <li><b>背压保护</b> — Stream 有最大长度限制，防止任务积压撑爆内存</li>
 * </ul>
 * <p>
 * 完整流程：
 * <pre>
 * POST /evaluation (REST)
 *   ↓
 * VoiceInterviewService.triggerEvaluation()
 *   ↓
 * VoiceEvaluationStreamProducer.publish(sessionId)  ← 本类
 *   ↓ (Redis Stream)
 * VoiceEvaluationStreamConsumer                     ← 消费者类
 *   ↓
 * VoiceEvaluationService.evaluate(sessionId) → 写回 evaluationJson + COMPLETED
 * </pre>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VoiceEvaluationStreamProducer {

    private final RedisService redisService;

    /** Stream 的 Redis Key。所有评估任务都发到这个 Stream。 */
    public static final String STREAM_KEY = "voice:evaluation:stream";

    /** Consumer Group 名称。同一个组内的消费者分摊消息。 */
    public static final String GROUP_NAME = "voice-evaluation-group";

    /** Stream 最大长度：保留最近 1000 条消息，超出时自动裁剪最旧的。 */
    private static final int MAX_STREAM_LENGTH = 1000;

    /**
     * 发布一个评估任务到 Redis Stream。
     * <p>
     * 消息字段：
     * <ul>
     *   <li>{@code sessionId} — 要评估的语音面试会话 ID</li>
     *   <li>{@code timestamp} — 发布时间，用于追踪和排查</li>
     * </ul>
     * <p>
     * 此方法是非阻塞的——消息入队后立即返回。
     * 消费者会在后台异步处理。
     *
     * @param sessionId 要评估的语音面试会话 ID
     */
    public void publish(Long sessionId) {
        // 先确保消费者组存在（幂等：已存在则忽略）
        redisService.createStreamGroup(STREAM_KEY, GROUP_NAME);

        // 发送消息到 Stream
        Map<String, String> message = Map.of(
                "sessionId", String.valueOf(sessionId),
                "timestamp", LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
        );

        String messageId = redisService.streamAdd(STREAM_KEY, message, MAX_STREAM_LENGTH);
        log.info("评估任务已发布到 Stream: sessionId={}, messageId={}", sessionId, messageId);
    }
}
