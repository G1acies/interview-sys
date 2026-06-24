package interview.guide.common.async;

import interview.guide.common.constant.AsyncTaskStreamConstants;
import interview.guide.infrastructure.redis.RedisService;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;

/**
 * Redis Stream 生产者模板基类。
 * <p>
 * 采用模板方法模式，将"发送任务到 Redis Stream"的公共逻辑固定下来，
 * 子类只需实现几个抽象方法定义具体业务数据。
 * <p>
 * <b>使用方式：</b>
 * <pre>{@code
 * public class VectorizeStreamProducer extends AbstractStreamProducer<VectorizeTaskPayload> {
 *     protected String streamKey() { return "knowledgebase:vectorize:stream"; }
 *     protected Map<String, String> buildMessage(VectorizeTaskPayload payload) { ... }
 *     protected void onSendFailed(VectorizeTaskPayload payload, String error) { ... }
 * }
 * }</pre>
 *
 * @param <T> 任务载荷类型（如 VectorizeTaskPayload、AnalyzePayload 等）
 */
@Slf4j
public abstract class AbstractStreamProducer<T> {

    private final RedisService redisService;

    protected AbstractStreamProducer(RedisService redisService) {
        this.redisService = redisService;
    }

    /**
     * 发送任务到 Redis Stream。
     * <p>
     * 将业务载荷序列化为 Map，通过 RedisService 写入 Stream。
     * 成功记录日志，失败时回调 {@link #onSendFailed} 让子类处理善后。
     *
     * @param payload 任务载荷
     */
    protected void sendTask(T payload) {
        try {
            String messageId = redisService.streamAdd(
                streamKey(),
                buildMessage(payload),
                AsyncTaskStreamConstants.STREAM_MAX_LEN
            );
            log.info("{}任务已发送到Stream: {}, messageId={}",
                taskDisplayName(), payloadIdentifier(payload), messageId);
        } catch (Exception e) {
            log.error("发送{}任务失败: {}, error={}",
                taskDisplayName(), payloadIdentifier(payload), e.getMessage(), e);
            onSendFailed(payload, "任务入队失败: " + e.getMessage());
        }
    }

    /**
     * 截断错误消息到 500 字符，避免数据库字段溢出。
     *
     * @param error 原始错误消息
     * @return 截断后的字符串
     */
    protected String truncateError(String error) {
        if (error == null) {
            return null;
        }
        return error.length() > 500 ? error.substring(0, 500) : error;
    }

    // ========== 子类需要实现的抽象方法 ==========

    /** 任务的中文名称（用于日志），如 "向量化"、"简历分析"。 */
    protected abstract String taskDisplayName();

    /** 目标 Redis Stream 的 Key。 */
    protected abstract String streamKey();

    /** 将业务载荷转换为 Redis Stream 消息的 Map（Key-Value 均为 String）。 */
    protected abstract Map<String, String> buildMessage(T payload);

    /** 任务载荷的文本标识（用于日志），如 "kbId=123"。 */
    protected abstract String payloadIdentifier(T payload);

    /**
     * 消息发送失败时的回调。
     * <p>
     * 通常在此将数据库中的任务状态更新为 FAILED，并记录错误原因。
     *
     * @param payload 任务载荷
     * @param error   错误描述
     */
    protected abstract void onSendFailed(T payload, String error);
}
