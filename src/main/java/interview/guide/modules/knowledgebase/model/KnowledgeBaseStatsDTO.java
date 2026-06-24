package interview.guide.modules.knowledgebase.model;

/**
 * 知识库统计信息 DTO。
 * <p>
 * 用于前端知识库管理页面的统计概览卡片。
 *
 * @param totalCount       知识库总数
 * @param totalQuestionCount 累计提问次数（不区分知识库）
 * @param totalAccessCount 累计访问次数
 * @param completedCount   向量化完成的知识库数量
 * @param processingCount  向量化处理中的知识库数量
 */
public record KnowledgeBaseStatsDTO(
    long totalCount,
    long totalQuestionCount,
    long totalAccessCount,
    long completedCount,
    long processingCount
) {
}
