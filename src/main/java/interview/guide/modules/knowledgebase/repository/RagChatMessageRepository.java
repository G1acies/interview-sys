package interview.guide.modules.knowledgebase.repository;

import interview.guide.modules.knowledgebase.model.RagChatMessageEntity;
import interview.guide.modules.knowledgebase.model.RagChatMessageEntity.MessageType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * RAG 聊天消息的数据访问接口。
 * <p>
 * 提供消息的按会话查询、顺序查询、流式占位消息查询等功能。
 * 消息在会话内按 {@code messageOrder} 排序，USER 和 ASSISTANT 交替出现。
 */
@Repository
public interface RagChatMessageRepository extends JpaRepository<RagChatMessageEntity, Long> {

    /**
     * 获取指定会话的所有消息，按消息序号升序排列。
     * <p>
     * 返回的消息顺序为：USER0, ASSISTANT0, USER1, ASSISTANT1, ...
     *
     * @param sessionId 会话 ID
     * @return 消息列表（按 messageOrder 升序）
     */
    List<RagChatMessageEntity> findBySessionIdOrderByMessageOrderAsc(Long sessionId);

    /**
     * 获取会话中的最后一条消息。
     *
     * @param sessionId 会话 ID
     * @return 最后一条消息（Optional）
     */
    Optional<RagChatMessageEntity> findTopBySessionIdOrderByMessageOrderDesc(Long sessionId);

    /**
     * 获取会话中最近 N 条已完成的消息（按 messageOrder 倒序）。
     * <p>
     * 用于构建多轮对话的历史上下文。
     * <b>注意：</b>返回结果是倒序的，调用方需要自行反转为正序。
     *
     * @param sessionId 会话 ID
     * @param pageable  分页参数（一般用 PageRequest.of(0, N) 限制条数）
     * @return 最近 N 条已完成的消息，按 messageOrder 倒序
     */
    @Query("SELECT m FROM RagChatMessageEntity m WHERE m.session.id = :sessionId AND m.completed = true ORDER BY m.messageOrder DESC")
    List<RagChatMessageEntity> findRecentCompletedBySessionId(@Param("sessionId") Long sessionId, Pageable pageable);

    /**
     * 统计指定会话的消息总数。
     *
     * @param sessionId 会话 ID
     * @return 消息数量
     */
    @Query("SELECT COUNT(m) FROM RagChatMessageEntity m WHERE m.session.id = :sessionId")
    Integer countBySessionId(@Param("sessionId") Long sessionId);

    /**
     * 查找会话中所有未完成的消息。
     * <p>
     * 用于流式响应中断后的清理——恢复时删除未完成的占位消息。
     *
     * @param sessionId 会话 ID
     * @return 未完成的消息列表
     */
    List<RagChatMessageEntity> findBySessionIdAndCompletedFalse(Long sessionId);

    /**
     * 删除指定会话的所有消息。
     * <p>
     * 删除会话时级联删除消息，通常情况下不需要手动调用。
     * 但独立清理消息场景（如重置对话）可能需要。
     *
     * @param sessionId 会话 ID
     */
    void deleteBySessionId(Long sessionId);

    /**
     * 按消息类型统计数量。
     * <p>
     * 用于统计面板：统计 USER 类型消息数 = 真实提问次数。
     *
     * @param type 消息类型（USER / ASSISTANT）
     * @return 该类型的消息数量
     */
    long countByType(MessageType type);
}
