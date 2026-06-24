package interview.guide.modules.knowledgebase.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * RAG 聊天消息实体。
 * <p>
 * 对应数据库 {@code rag_chat_messages} 表。
 * 存储用户问题（USER）和 AI 回答（ASSISTANT），一对多关联到 {@link RagChatSessionEntity}。
 * <p>
 * <b>流式响应处理：</b>
 * <ol>
 *   <li>保存用户消息（{@code completed = true}）</li>
 *   <li>创建 AI 消息占位（{@code completed = false}，内容为空字符串）</li>
 *   <li>流式生成过程中逐步写入内容</li>
 *   <li>完成后设置 {@code completed = true}，写入完整内容</li>
 * </ol>
 * 这种设计保证即使流式中断，前端也能展示部分内容而非完全丢失。
 */
@Entity
@Table(name = "rag_chat_messages", indexes = {
    @Index(name = "idx_rag_message_session", columnList = "session_id"),
    @Index(name = "idx_rag_message_order", columnList = "session_id, messageOrder")
})
@Getter
@Setter
@NoArgsConstructor
public class RagChatMessageEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 所属的 RAG 会话。延迟加载。 */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "session_id", nullable = false)
    private RagChatSessionEntity session;

    /** 消息类型：USER（用户）或 ASSISTANT（AI）。 */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private MessageType type;

    /** 消息内容。USER 消息直接存用户输入，ASSISTANT 消息存 Markdown 格式的 AI 回答。 */
    @Column(columnDefinition = "TEXT", nullable = false)
    private String content;

    /**
     * 消息在会话中的序号（从 0 递增）。
     * 偶数 = USER，奇数 = ASSISTANT，每对 (0,1)、(2,3) 构成一轮对话。
     */
    @Column(nullable = false)
    private Integer messageOrder;

    /** 创建时间。由 {@code @PrePersist} 自动设置。 */
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /** 更新时间（流式内容更新时修改）。 */
    private LocalDateTime updatedAt;

    /**
     * 消息是否已完成生成。
     * <ul>
     *   <li>USER 消息：发送即完成，始终 true</li>
     *   <li>ASSISTANT 消息：创建占位时 false，流式完成后设为 true</li>
     * </ul>
     */
    private Boolean completed = true;

    /** 消息类型枚举。 */
    public enum MessageType {
        USER,      // 用户发送的问题
        ASSISTANT  // AI 生成的回答
    }

    // ========== 生命周期回调 ==========

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    /**
     * 获取消息类型的小写字符串，供前端使用。
     *
     * @return "user" 或 "assistant"
     */
    public String getTypeString() {
        return type.name().toLowerCase();
    }
}
