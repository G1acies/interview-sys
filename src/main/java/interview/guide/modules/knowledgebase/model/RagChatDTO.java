package interview.guide.modules.knowledgebase.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

import java.time.LocalDateTime;
import java.util.List;

/**
 * RAG 聊天相关 DTO 集合。
 * <p>
 * 包含请求和响应两类 DTO，统一放在一个文件中方便管理。
 * 所有 DTO 均为不可变的 {@code record} 类型。
 */
public class RagChatDTO {

    // ==================================================================
    // 请求 DTO
    // ==================================================================

    /**
     * 创建会话请求。
     *
     * @param knowledgeBaseIds 关联的知识库 ID 列表（至少选一个）
     * @param title            会话标题（可选，为空则自动从知识库名称生成）
     */
    public record CreateSessionRequest(
        @NotEmpty(message = "至少选择一个知识库")
        List<Long> knowledgeBaseIds,

        String title
    ) {}

    /**
     * 发送消息请求。
     *
     * @param question 用户输入的问题
     */
    public record SendMessageRequest(
        @NotBlank(message = "问题不能为空")
        String question
    ) {}

    /**
     * 更新会话标题请求。
     *
     * @param title 新标题
     */
    public record UpdateTitleRequest(
        @NotBlank(message = "标题不能为空")
        String title
    ) {}

    /**
     * 更新会话关联知识库请求。
     *
     * @param knowledgeBaseIds 新的知识库 ID 列表
     */
    public record UpdateKnowledgeBasesRequest(
        @NotEmpty(message = "至少选择一个知识库")
        List<Long> knowledgeBaseIds
    ) {}

    // ==================================================================
    // 响应 DTO
    // ==================================================================

    /**
     * 会话基础信息。
     *
     * @param id               会话 ID
     * @param title            会话标题
     * @param knowledgeBaseIds 关联的知识库 ID 列表
     * @param createdAt        创建时间
     */
    public record SessionDTO(
        Long id,
        String title,
        List<Long> knowledgeBaseIds,
        LocalDateTime createdAt
    ) {}

    /**
     * 会话列表项（供左侧列表使用）。
     *
     * @param id                 会话 ID
     * @param title              会话标题
     * @param messageCount       消息总条数
     * @param knowledgeBaseNames 关联知识库的名称列表
     * @param updatedAt          最后更新时间
     * @param isPinned           是否置顶
     */
    public record SessionListItemDTO(
        Long id,
        String title,
        Integer messageCount,
        List<String> knowledgeBaseNames,
        LocalDateTime updatedAt,
        Boolean isPinned
    ) {}

    /**
     * 会话详情（包含消息列表和关联知识库）。
     *
     * @param id            会话 ID
     * @param title         会话标题
     * @param knowledgeBases 关联的知识库列表（含完整信息）
     * @param messages      消息历史列表
     * @param createdAt     创建时间
     * @param updatedAt     最后更新时间
     */
    public record SessionDetailDTO(
        Long id,
        String title,
        List<KnowledgeBaseListItemDTO> knowledgeBases,
        List<MessageDTO> messages,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
    ) {}

    /**
     * 单条消息 DTO。
     *
     * @param id        消息 ID
     * @param type      消息类型：{@code "user"} 或 {@code "assistant"}
     * @param content   消息内容（USER=纯文本，ASSISTANT=Markdown）
     * @param createdAt 发送时间
     */
    public record MessageDTO(
        Long id,
        String type,
        String content,
        LocalDateTime createdAt
    ) {}
}
