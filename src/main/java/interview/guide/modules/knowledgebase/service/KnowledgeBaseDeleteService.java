package interview.guide.modules.knowledgebase.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 知识库删除服务。
 * <p>
 * 级联删除知识库的所有关联数据，按以下顺序执行：
 * <ol>
 *   <li>从所有 RAG 会话中移除该知识库的关联（外键约束）</li>
 *   <li>删除 pgvector 中的向量数据</li>
 *   <li>删除 MinIO 中的原始文件</li>
 *   <li>删除数据库中的知识库记录</li>
 * </ol>
 * 每一步都独立 try-catch，单步失败不影响后续步骤。
 */
@Service
public class KnowledgeBaseDeleteService {

    /**
     * 级联删除知识库。
     * <p>
     * 删除前会先解除所有 RAG 会话对该知识库的关联，
     * 避免外键约束导致删除失败。
     *
     * @param id 知识库 ID
     */
    @Transactional(rollbackFor = Exception.class)
    public void deleteKnowledgeBase(Long id) {
        // TODO
    }
}
