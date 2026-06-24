package interview.guide.modules.knowledgebase.repository;

import interview.guide.modules.knowledgebase.model.RagChatSessionEntity;
import interview.guide.modules.knowledgebase.model.RagChatSessionEntity.SessionStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * RAG 聊天会话的数据访问接口。
 * <p>
 * 提供会话的 CRUD、置顶排序、知识库关联查询等功能。
 * 会话列表默认按"置顶优先 + 最近更新"排序。
 */
@Repository
public interface RagChatSessionRepository extends JpaRepository<RagChatSessionEntity, Long> {

    /**
     * 按更新时间倒序获取所有指定状态的会话。
     *
     * @param status 会话状态（ACTIVE / ARCHIVED）
     */
    List<RagChatSessionEntity> findByStatusOrderByUpdatedAtDesc(SessionStatus status);

    /**
     * 获取所有会话，按更新时间倒序。
     */
    List<RagChatSessionEntity> findAllByOrderByUpdatedAtDesc();

    /**
     * 获取所有会话，按"置顶优先 + 最近更新"排序。
     * <p>
     * 置顶会话（isPinned = true）排在最前，然后按 updatedAt 降序。
     * 这是前端会话列表的默认排序方式。
     */
    @Query("SELECT s FROM RagChatSessionEntity s ORDER BY s.isPinned DESC, s.updatedAt DESC")
    List<RagChatSessionEntity> findAllOrderByPinnedAndUpdatedAtDesc();

    /**
     * 根据知识库 ID 查找关联了该知识库的所有会话。
     * <p>
     * 删除知识库时需要调用此方法，将知识库从会话中移除后再删除。
     * 使用 DISTINCT 避免多对多关联产生的重复行。
     *
     * @param kbIds 知识库 ID 列表
     * @return 关联了这些知识库的会话列表
     */
    @Query("SELECT DISTINCT s FROM RagChatSessionEntity s JOIN s.knowledgeBases kb WHERE kb.id IN :kbIds ORDER BY s.updatedAt DESC")
    List<RagChatSessionEntity> findByKnowledgeBaseIds(@Param("kbIds") List<Long> knowledgeBaseIds);

    /**
     * 获取会话详情（含关联的知识库列表）。
     * <p>
     * 使用 LEFT JOIN FETCH 一次性加载知识库关联，避免 N+1 查询。
     * 此方法不加载消息列表——消息需通过 {@link RagChatMessageRepository} 单独查询，
     * 避免多对多 + 一对多同时 FETCH 导致的笛卡尔积。
     *
     * @param id 会话 ID
     * @return 会话（含知识库关联）
     */
    @Query("SELECT s FROM RagChatSessionEntity s LEFT JOIN FETCH s.knowledgeBases WHERE s.id = :id")
    Optional<RagChatSessionEntity> findByIdWithKnowledgeBases(@Param("id") Long id);

    /**
     * 获取会话详情（含关联的知识库列表和消息列表）。
     * <p>
     * 同时 FETCH knowledgeBases（多对多）和 messages（一对多）可能产生笛卡尔积，
     * 如果数据量大时请注意性能。大多数场景推荐 {@link #findByIdWithKnowledgeBases} +
     * 单独查询消息的组合方式。
     *
     * @param id 会话 ID
     * @return 会话（含知识库和消息关联）
     */
    @Query("SELECT DISTINCT s FROM RagChatSessionEntity s LEFT JOIN FETCH s.knowledgeBases WHERE s.id = :id")
    Optional<RagChatSessionEntity> findByIdWithMessagesAndKnowledgeBases(@Param("id") Long id);
}
