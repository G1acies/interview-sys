package interview.guide.modules.knowledgebase.listener;

import interview.guide.common.async.AbstractStreamConsumer;
import interview.guide.common.constant.AsyncTaskStreamConstants;
import interview.guide.infrastructure.redis.RedisService;
import interview.guide.modules.knowledgebase.model.VectorStatus;
import interview.guide.modules.knowledgebase.repository.KnowledgeBaseRepository;
import interview.guide.modules.knowledgebase.service.KnowledgeBaseVectorService;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.stream.StreamMessageId;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 知识库向量化任务的消费者。
 * <p>
 * 作为后台守护线程运行，从 {@code knowledgebase:vectorize:stream} 拉取消息，
 * 调用 {@link KnowledgeBaseVectorService#vectorizeAndStore} 执行实际的向量化操作。
 * <p>
 * <b>处理流程（每条消息的生命周期）：</b>
 * <ol>
 *   <li>{@code validateEntityExists} — 检查知识库实体是否仍存在（被删除则跳过）</li>
 *   <li>{@code markProcessing} — 状态 → {@link VectorStatus#PROCESSING}</li>
 *   <li>{@code processBusiness} — 分块 → Embedding → 写入 pgvector</li>
 *   <li>{@code markCompleted} — 状态 → {@link VectorStatus#COMPLETED}</li>
 *   <li>ACK 消息 — 从 Pending List 移除</li>
 * </ol>
 * <p>
 * <b>失败重试：</b>最多 3 次，超过后标记 {@link VectorStatus#FAILED}。
 */
@Slf4j
@Component
public class VectorizeStreamConsumer extends AbstractStreamConsumer<VectorizeStreamConsumer.VectorizePayload> {

    private final KnowledgeBaseVectorService vectorService;
    private final KnowledgeBaseRepository knowledgeBaseRepository;

    /**
     * 向量化任务的内部载荷。
     *
     * @param kbId    知识库 ID
     * @param content 待向量化的文档纯文本
     */
    record VectorizePayload(Long kbId, String content) {}

    public VectorizeStreamConsumer(
        RedisService redisService,
        KnowledgeBaseVectorService vectorService,
        KnowledgeBaseRepository knowledgeBaseRepository
    ) {
        super(redisService);
        this.vectorService = vectorService;
        this.knowledgeBaseRepository = knowledgeBaseRepository;
    }

    @Override
    protected String taskDisplayName() { return "向量化"; }

    @Override
    protected String streamKey() { return AsyncTaskStreamConstants.KB_VECTORIZE_STREAM_KEY; }

    @Override
    protected String groupName() { return AsyncTaskStreamConstants.KB_VECTORIZE_GROUP_NAME; }

    @Override
    protected String consumerPrefix() { return AsyncTaskStreamConstants.KB_VECTORIZE_CONSUMER_PREFIX; }

    @Override
    protected String threadName() { return "vectorize-consumer"; }

    @Override
    protected String payloadIdentifier(VectorizePayload payload) { return "kbId=" + payload.kbId(); }

    // ========== 前置校验 ==========

    /**
     * 消费前检查知识库实体是否仍存在。
     * <p>
     * 用户可能在任务入队后删除了知识库，此时直接跳过处理并 ACK，
     * 避免对已删除资源做无意义的向量化。
     */
    @Override
    protected boolean validateEntityExists(VectorizePayload payload) {
        return knowledgeBaseRepository.existsById(payload.kbId());
    }

    // ========== 消息解析 ==========

    @Override
    protected VectorizePayload parsePayload(StreamMessageId messageId, Map<String, String> data) {
        String kbIdStr = data.get(AsyncTaskStreamConstants.FIELD_KB_ID);
        String content = data.get(AsyncTaskStreamConstants.FIELD_CONTENT);
        if (kbIdStr == null || content == null) {
            log.warn("消息格式错误，跳过: messageId={}", messageId);
            return null;
        }
        return new VectorizePayload(Long.parseLong(kbIdStr), content);
    }

    // ========== 状态管理 ==========

    @Override
    protected void markProcessing(VectorizePayload payload) {
        updateVectorStatus(payload.kbId(), VectorStatus.PROCESSING, null);
    }

    @Override
    protected void processBusiness(VectorizePayload payload) {
        vectorService.vectorizeAndStore(payload.kbId(), payload.content());
    }

    @Override
    protected void markCompleted(VectorizePayload payload) {
        updateVectorStatus(payload.kbId(), VectorStatus.COMPLETED, null);
    }

    @Override
    protected void markFailed(VectorizePayload payload, String error) {
        updateVectorStatus(payload.kbId(), VectorStatus.FAILED, error);
    }

    // ========== 重试机制 ==========

    /**
     * 重试向量化任务。
     * <p>
     * 将原任务重新入队，retryCount+1。
     * 消费者基类会在重试次数达到 {@link AsyncTaskStreamConstants#MAX_RETRY_COUNT}（3 次）
     * 后自动调用 {@link #markFailed} 停止重试。
     */
    @Override
    protected void retryMessage(VectorizePayload payload, int retryCount) {
        Long kbId = payload.kbId();
        String content = payload.content();
        try {
            Map<String, String> message = Map.of(
                AsyncTaskStreamConstants.FIELD_KB_ID, kbId.toString(),
                AsyncTaskStreamConstants.FIELD_CONTENT, content,
                AsyncTaskStreamConstants.FIELD_RETRY_COUNT, String.valueOf(retryCount)
            );

            redisService().streamAdd(
                AsyncTaskStreamConstants.KB_VECTORIZE_STREAM_KEY,
                message,
                AsyncTaskStreamConstants.STREAM_MAX_LEN
            );
            log.info("向量化任务已重新入队: kbId={}, retryCount={}", kbId, retryCount);

        } catch (Exception e) {
            log.error("重试入队失败: kbId={}, error={}", kbId, e.getMessage(), e);
            updateVectorStatus(kbId, VectorStatus.FAILED, truncateError("重试入队失败: " + e.getMessage()));
        }
    }

    // ========== 内部方法 ==========

    /**
     * 更新知识库的向量化状态。
     * <p>
     * 使用 try-catch 包裹，确保状态更新失败不会影响消息 ACK 流程。
     *
     * @param kbId   知识库 ID
     * @param status 目标状态
     * @param error  错误信息（成功时为 null）
     */
    private void updateVectorStatus(Long kbId, VectorStatus status, String error) {
        try {
            knowledgeBaseRepository.findById(kbId).ifPresent(kb -> {
                kb.setVectorStatus(status);
                kb.setVectorError(error);
                knowledgeBaseRepository.save(kb);
                log.debug("向量化状态已更新: kbId={}, status={}", kbId, status);
            });
        } catch (Exception e) {
            log.error("更新向量化状态失败: kbId={}, status={}, error={}", kbId, status, e.getMessage(), e);
        }
    }
}
