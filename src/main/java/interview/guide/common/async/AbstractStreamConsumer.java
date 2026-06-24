package interview.guide.common.async;

import interview.guide.common.constant.AsyncTaskStreamConstants;
import interview.guide.infrastructure.redis.RedisService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.stream.StreamMessageId;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Redis Stream 消费者模板基类。
 * <p>
 * 采用模板方法模式，封装了完整的消费者生命周期：
 * <ol>
 *   <li>{@code @PostConstruct init()} — 启动后台守护线程</li>
 *   <li>{@code consumeLoop()} — 轮询 Stream，拉取消息</li>
 *   <li>{@code processMessage()} — 解析 → 标记处理中 → 执行业务 → 标记完成 → ACK</li>
 *   <li>失败重试（最多 3 次）→ 超过重试次数标记 FAILED</li>
 *   <li>{@code @PreDestroy shutdown()} — 应用关闭时优雅停止</li>
 * </ol>
 * <p>
 * <b>运行机制：</b>
 * 每个消费者实例启动一个独立的守护线程，属于同一个 Consumer Group。
 * 同一个 Stream 下的多个消费者实例会自动负载均衡——每条消息只会被其中一个消费。
 * <p>
 * <b>使用方式：</b>
 * <pre>{@code
 * @Component
 * public class VectorizeStreamConsumer extends AbstractStreamConsumer<VectorizePayload> {
 *     protected String streamKey() { return "knowledgebase:vectorize:stream"; }
 *     protected String groupName() { return "vectorize-group"; }
 *     protected VectorizePayload parsePayload(...) { ... }
 *     protected void processBusiness(VectorizePayload payload) { ... }
 *     // ... 其他抽象方法
 * }
 * }</pre>
 *
 * @param <T> 任务载荷类型
 */
@Slf4j
public abstract class AbstractStreamConsumer<T> {

    private final RedisService redisService;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private ExecutorService executorService;
    private String consumerName;

    protected AbstractStreamConsumer(RedisService redisService) {
        this.redisService = redisService;
    }

    /**
     * 初始化消费者。
     * <p>
     * Spring 启动时自动调用（{@code @PostConstruct}），
     * 创建一个单线程的后台守护线程开始轮询 Stream。
     */
    @PostConstruct
    public void init() {
        this.consumerName = consumerPrefix() + UUID.randomUUID().toString().substring(0, 8);
        this.executorService = new ThreadPoolExecutor(
            1,
            1,
            0L,
            TimeUnit.MILLISECONDS,
            new LinkedBlockingQueue<>(),
            r -> {
                Thread t = new Thread(r, threadName());
                t.setDaemon(true);
                return t;
            },
            new ThreadPoolExecutor.AbortPolicy()
        );

        running.set(true);
        executorService.submit(this::startConsumer);
        log.info("{} consumer started: consumerName={}", taskDisplayName(), consumerName);
    }

    /**
     * 优雅关闭消费者。
     * <p>
     * 应用关闭时（{@code @PreDestroy}）自动调用，
     * 设置停止标志 → 线程池不再接受新任务 → 等待当前任务完成。
     */
    @PreDestroy
    public void shutdown() {
        running.set(false);
        if (executorService != null) {
            executorService.shutdown();
        }
        log.info("{} consumer stopped: consumerName={}", taskDisplayName(), consumerName);
    }

    /**
     * 启动消费者循环前的准备工作。
     * <p>
     * 确保 Redis Stream 和 Consumer Group 已创建，
     * 然后进入无限消费循环。
     */
    private void startConsumer() {
        try {
            redisService.createStreamGroup(streamKey(), groupName());
            log.info("Redis Stream group is ready: {}", groupName());
        } catch (Exception e) {
            log.warn("Failed to prepare Redis Stream group: groupName={}", groupName(), e);
        }

        consumeLoop();
    }

    /**
     * 主消费循环。
     * <p>
     * 使用 Redis BLOCK 模式阻塞等待消息（而非客户端轮询），
     * 服务端有新消息时立即返回，减少无效网络开销。
     */
    private void consumeLoop() {
        while (running.get()) {
            try {
                redisService.streamConsumeMessages(
                    streamKey(),
                    groupName(),
                    consumerName,
                    AsyncTaskStreamConstants.BATCH_SIZE,
                    AsyncTaskStreamConstants.POLL_INTERVAL_MS,
                    this::processMessage
                );
            } catch (Exception e) {
                if (Thread.currentThread().isInterrupted()) {
                    log.info("Consumer thread interrupted");
                    break;
                }
                log.error("Failed to consume message", e);
            }
        }
    }

    /**
     * 处理单条消息的完整生命周期。
     * <p>
     * 流程：解析 → 实体存在性校验（由子类实现）→ 标记处理中 → 执行业务 → 标记完成 → ACK
     * <p>
     * <b>失败重试策略：</b>
     * <ul>
     *   <li>重试次数 < 3：重新入队（retryCount+1），等待下次消费</li>
     *   <li>重试次数 ≥ 3：标记 FAILED，记录错误信息</li>
     * </ul>
     * 无论成功或失败，最终都会 ACK 该消息，避免消息堆积。
     *
     * @param messageId Stream 消息 ID
     * @param data      消息内容（Key-Value Map）
     */
    private void processMessage(StreamMessageId messageId, Map<String, String> data) {
        T payload = parsePayload(messageId, data);
        if (payload == null) {
            ackMessage(messageId);
            return;
        }

        int retryCount = parseRetryCount(data);
        log.info("Processing {} task: payload={}, messageId={}, retryCount={}",
            taskDisplayName(), payloadIdentifier(payload), messageId, retryCount);

        try {
            // 前置校验：实体是否存在（不存在则直接 ACK 丢弃）
            if (!validateEntityExists(payload)) {
                log.warn("{} task target entity not found, skip: payload={}",
                    taskDisplayName(), payloadIdentifier(payload));
                ackMessage(messageId);
                return;
            }

            markProcessing(payload);
            processBusiness(payload);
            markCompleted(payload);
            ackMessage(messageId);
            log.info("{} task completed: {}", taskDisplayName(), payloadIdentifier(payload));
        } catch (Exception e) {
            log.error("{} task failed: {}", taskDisplayName(), payloadIdentifier(payload), e);
            if (retryCount < AsyncTaskStreamConstants.MAX_RETRY_COUNT) {
                retryMessage(payload, retryCount + 1);
            } else {
                markFailed(payload, truncateError(
                    taskDisplayName() + " failed after retry " + retryCount + ": " + e.getMessage()
                ));
            }
            ackMessage(messageId);
        }
    }

    /**
     * 从消息的 Map 数据中解析重试次数。
     * 如果字段不存在或无法解析，返回 0。
     */
    protected int parseRetryCount(Map<String, String> data) {
        try {
            return Integer.parseInt(data.getOrDefault(AsyncTaskStreamConstants.FIELD_RETRY_COUNT, "0"));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * 截断错误消息到 500 字符，避免数据库字段溢出。
     */
    protected String truncateError(String error) {
        if (error == null) {
            return null;
        }
        return error.length() > 500 ? error.substring(0, 500) : error;
    }

    /**
     * ACK 确认消息已被处理，Redis 会将其从 Pending List 中移除。
     */
    private void ackMessage(StreamMessageId messageId) {
        try {
            redisService.streamAck(streamKey(), groupName(), messageId);
        } catch (Exception e) {
            log.error("Failed to ack stream message: messageId={}", messageId, e);
        }
    }

    /**
     * 暴露 RedisService 给子类使用（如重试入队时需要调用 streamAdd）。
     */
    protected RedisService redisService() {
        return redisService;
    }

    // ========== 子类需要实现的抽象方法 ==========

    /** 任务的中文名称（用于日志），如 "向量化"。 */
    protected abstract String taskDisplayName();

    /** Redis Stream 的 Key。需与 {@link AbstractStreamProducer#streamKey()} 一致。 */
    protected abstract String streamKey();

    /** Consumer Group 名称。同组消费者负载均衡。 */
    protected abstract String groupName();

    /** Consumer 名称前缀，后面会追加随机 UUID。 */
    protected abstract String consumerPrefix();

    /** 后台线程名，便于排查问题。 */
    protected abstract String threadName();

    /** 从 Redis Stream 消息的 Map 中解析出业务载荷。返回 null 时跳过该消息。 */
    protected abstract T parsePayload(StreamMessageId messageId, Map<String, String> data);

    /** 任务载荷的文本标识（用于日志）。 */
    protected abstract String payloadIdentifier(T payload);

    /** 前置校验：任务目标实体是否仍然存在。不存在则跳过处理。默认返回 true。 */
    protected boolean validateEntityExists(T payload) {
        return true;
    }

    /** 将业务状态更新为"处理中"。如 knowledge_bases.vectorStatus = PROCESSING。 */
    protected abstract void markProcessing(T payload);

    /** 执行实际的业务逻辑（耗时操作在此发生）。 */
    protected abstract void processBusiness(T payload);

    /** 将业务状态更新为"已完成"。如 knowledge_bases.vectorStatus = COMPLETED。 */
    protected abstract void markCompleted(T payload);

    /** 将业务状态更新为"失败"，记录错误信息。 */
    protected abstract void markFailed(T payload, String error);

    /** 重新将任务入队，retryCount+1，等待下次重试。 */
    protected abstract void retryMessage(T payload, int retryCount);
}
