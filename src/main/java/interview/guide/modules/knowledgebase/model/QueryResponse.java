package interview.guide.modules.knowledgebase.model;

/**
 * 知识库查询响应。
 *
 * @param answer            AI 生成的回答内容
 * @param knowledgeBaseId   主知识库 ID（多个知识库时取第一个，方便前端识别）
 * @param knowledgeBaseName 知识库名称（多个时用顿号分隔）
 */
public record QueryResponse(
    String answer,
    Long knowledgeBaseId,
    String knowledgeBaseName
) {
}
