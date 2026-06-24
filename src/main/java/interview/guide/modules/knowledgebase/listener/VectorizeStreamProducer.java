package interview.guide.modules.knowledgebase.listener;

import interview.guide.common.async.AbstractStreamProducer;
import interview.guide.common.constant.AsyncTaskStreamConstants;
import interview.guide.infrastructure.redis.RedisService;
import interview.guide.modules.knowledgebase.model.VectorStatus;
import interview.guide.modules.knowledgebase.repository.KnowledgeBaseRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 知识库向量化任务的生产者。
 * <p>
 * 负责将"向量化任务"包装为 Redis Stream 消息并发送到
 * {@code knowledgebase:vectorize:stream} 队列。
 * <p>
 * <b>触发时机：</b>
 * 用户在知识库上传页面提交文件后，由 {@link interview.guide.modules.knowledgebase.service.KnowledgeBaseManageService}
 * 调用 {@link #sendVectorizeTask(Long, String)}。
 * <p>
 * <b>消息格式：</b>
 * <pre>
 * {
 *   "kbId":       "123",       // 知识库 ID
 *   "content":    "文档纯文本", // Tika 解析出的文本内容
 *   "retryCount": "0"          // 初始重试次数
 * }
 * </pre>
 */
@Slf4j
@Component
public class VectorizeStreamProducer extends AbstractStreamProducer<VectorizeStreamProducer.VectorizeTaskPayload> {

    private final KnowledgeBaseRepository knowledgeBaseRepository;

    /**
     * 向量化任务的内部载荷。
     *
     * @param kbId    知识库 ID
     * @param content 文档解析后的纯文本内容
     */
    record VectorizeTaskPayload(Long kbId, String content) {}

    public VectorizeStreamProducer(RedisService redisService, KnowledgeBaseRepository knowledgeBaseRepository) {
        super(redisService);
        this.knowledgeBaseRepository = knowledgeBaseRepository;
    }

    /**
     * 发送向量化任务到 Redis Stream。
     * <p>
     * 上传流程中最后一步调用此方法，将任务异步化：
     * <ol>
     *   <li>上传文件 → 保存到 MinIO</li>
     *   <li>Tika 解析文本内容</li>
     *   <li>保存元数据到数据库（状态 = PENDING）</li>
     *   <li><b>→ 发送向量化任务到 Stream（异步）</b></li>
     * </ol>
     *
     * @param kbId    知识库 ID
     * @param content 文档的纯文本内容
     */
    public void sendVectorizeTask(Long kbId, String content) {
        sendTask(new VectorizeTaskPayload(kbId, content));
    }

    @Override
    protected String taskDisplayName() {
        return "向量化";
    }

    @Override
    protected String streamKey() {
        return AsyncTaskStreamConstants.KB_VECTORIZE_STREAM_KEY;
    }

    @Override
    protected Map<String, String> buildMessage(VectorizeTaskPayload payload) {
        return Map.of(
            AsyncTaskStreamConstants.FIELD_KB_ID, payload.kbId().toString(),
            AsyncTaskStreamConstants.FIELD_CONTENT, payload.content(),
            AsyncTaskStreamConstants.FIELD_RETRY_COUNT, "0"
        );
    }

    @Override
    protected String payloadIdentifier(VectorizeTaskPayload payload) {
        return "kbId=" + payload.kbId();
    }

    /**
     * 发送失败的回调。
     * <p>
     * 网络故障或 Redis 不可用时，直接将知识库状态标记为 FAILED，
     * 用户可以在前端看到并手动点击"重新向量化"重试。
     */
    @Override
    protected void onSendFailed(VectorizeTaskPayload payload, String error) {
        updateVectorStatus(payload.kbId(), VectorStatus.FAILED, truncateError(error));
    }

    /**
     * 更新知识库的向量化状态。
     * <p>
     * 使用 findById + ifPresent 安全更新，即使实体已被删除也不会抛异常。
     *
     * @param kbId   知识库 ID
     * @param status 目标状态
     * @param error  错误信息（成功时为 null）
     */
    private void updateVectorStatus(Long kbId, VectorStatus status, String error) {
        knowledgeBaseRepository.findById(kbId).ifPresent(kb -> {
            kb.setVectorStatus(status);
            if (error != null) {
                kb.setVectorError(error.length() > 500 ? error.substring(0, 500) : error);
            }
            knowledgeBaseRepository.save(kb);
        });
    }
}
