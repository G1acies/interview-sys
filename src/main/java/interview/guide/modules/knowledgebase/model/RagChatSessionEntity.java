package interview.guide.modules.knowledgebase.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * RAG 聊天会话实体。
 * <p>
 * 对应数据库 {@code rag_chat_sessions} 表。
 * 一个会话代表一次 RAG 对话，可以关联多个知识库，包含多条消息。
 * <p>
 * <b>关联关系：</b>
 * <ul>
 *   <li>{@code knowledgeBases} — ManyToMany，通过中间表 {@code rag_session_knowledge_bases} 关联</li>
 *   <li>{@code messages} — OneToMany，级联删除（删除会话时自动删除所有消息）</li>
 * </ul>
 * <p>
 * <b>置顶功能：</b>
 * {@code isPinned} 字段控制会话是否在列表顶部显示，最近更新的置顶会话靠前。
 */
@Entity
@Table(name = "rag_chat_sessions", indexes = {
    @Index(name = "idx_rag_session_updated", columnList = "updatedAt")
})
@Getter
@Setter
@NoArgsConstructor
public class RagChatSessionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 会话标题。创建时自动生成（基于关联知识库名称），用户可手动修改。 */
    @Column(nullable = false)
    private String title;

    /** 会话状态。默认 {@link SessionStatus#ACTIVE}，保留 {@code ARCHIVED} 扩展。 */
    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private SessionStatus status = SessionStatus.ACTIVE;

    /**
     * 多对多：会话关联的知识库。
     * 通过中间表 rag_session_knowledge_bases 关联。
     */
    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(
        name = "rag_session_knowledge_bases",
        joinColumns = @JoinColumn(name = "session_id"),
        inverseJoinColumns = @JoinColumn(name = "knowledge_base_id")
    )
    private Set<KnowledgeBaseEntity> knowledgeBases = new HashSet<>();

    /**
     * 一对多：会话的消息列表。
     * 级联 ALL + orphanRemoval，删除会话时自动删除所有消息。
     */
    @OneToMany(mappedBy = "session", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("messageOrder ASC")
    private List<RagChatMessageEntity> messages = new ArrayList<>();

    /** 创建时间。由 {@code @PrePersist} 自动设置。 */
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /** 最后更新时间（最后一条消息的发送时间）。 */
    private LocalDateTime updatedAt;

    /** 消息总数（冗余字段，避免 COUNT 查询）。 */
    private Integer messageCount = 0;

    /** 是否置顶。置顶会话在列表中靠前显示。默认 false。 */
    @Column(columnDefinition = "boolean default false")
    private Boolean isPinned = false;

    /** 会话状态枚举。 */
    public enum SessionStatus {
        ACTIVE,    // 活跃会话
        ARCHIVED   // 已归档（暂未使用，预留扩展）
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

    @PostLoad
    protected void onLoad() {
        // 兼容旧数据：确保 isPinned 不为 null
        if (isPinned == null) {
            isPinned = false;
        }
    }

    // ========== 便捷方法 ==========

    /** 添加消息并自动更新 messageCount 和 updatedAt。 */
    public void addMessage(RagChatMessageEntity message) {
        messages.add(message);
        message.setSession(this);
        messageCount = messages.size();
        updatedAt = LocalDateTime.now();
    }

    /** 获取关联知识库的 ID 列表。 */
    public List<Long> getKnowledgeBaseIds() {
        return knowledgeBases.stream()
            .map(KnowledgeBaseEntity::getId)
            .toList();
    }
}
