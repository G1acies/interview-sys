package interview.guide.modules.knowledgebase.model;

/**
 * 知识库文档的向量化状态枚举。
 * <p>
 * 一份文档上传后经历的完整生命周期：
 * <pre>
 * 上传 → PENDING ──→ PROCESSING ──→ COMPLETED
 *                    ↘ 失败 → FAILED
 * </pre>
 * <ul>
 *   <li><b>PENDING</b> — 文件已保存，向量化任务已入 Redis Stream 队列，待消费</li>
 *   <li><b>PROCESSING</b> — 消费者正在执行向量化（文本分块 + Embedding + 写入 pgvector）</li>
 *   <li><b>COMPLETED</b> — 向量化成功，可参与 RAG 检索</li>
 *   <li><b>FAILED</b> — 向量化失败，可在前端查看错误原因并点击"重新向量化"重试</li>
 * </ul>
 */
public enum VectorStatus {

    PENDING,
    PROCESSING,
    COMPLETED,
    FAILED
}
