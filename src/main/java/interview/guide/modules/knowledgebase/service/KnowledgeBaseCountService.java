package interview.guide.modules.knowledgebase.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 知识库提问计数服务。
 * <p>
 * 每次 RAG 查询时更新对应知识库的提问次数。
 * 使用批量 SQL 更新避免 N+1 查询，并做去重和存在性校验。
 */
@Service
public class KnowledgeBaseCountService {

    /**
     * 批量增加知识库提问计数。
     * <p>
     * 每个知识库的 questionCount +1，表示该知识库参与了一次回答。
     * 使用单条 SQL 批量更新，原子且高效。
     *
     * @param knowledgeBaseIds 参与回答的知识库 ID 列表
     */
    @Transactional(rollbackFor = Exception.class)
    public void updateQuestionCounts(List<Long> knowledgeBaseIds) {
        // TODO
    }
}
