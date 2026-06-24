package interview.guide.modules.knowledgebase.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

/**
 * 知识库查询请求。
 * <p>
 * 支持同时查询多个知识库（RAG 多库检索）。
 * 前端传入知识库 ID 列表和用户问题，后端从对应知识库中检索相关内容后生成回答。
 *
 * @param knowledgeBaseIds 目标知识库的 ID 列表（至少选一个）
 * @param question         用户问题（不能为空）
 */
public record QueryRequest(
    @NotEmpty(message = "至少选择一个知识库")
    List<Long> knowledgeBaseIds,

    @NotBlank(message = "问题不能为空")
    String question
) {
    /**
     * 兼容单知识库查询的便捷构造方法。
     *
     * @param knowledgeBaseId 单个知识库 ID
     * @param question        用户问题
     */
    public QueryRequest(Long knowledgeBaseId, String question) {
        this(List.of(knowledgeBaseId), question);
    }
}
