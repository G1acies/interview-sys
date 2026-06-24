package interview.guide.modules.knowledgebase.repository;

import interview.guide.modules.knowledgebase.model.KnowledgeBaseEntity;
import interview.guide.modules.knowledgebase.model.VectorStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 知识库文档的数据访问接口。
 * <p>
 * 提供知识库实体的 CRUD、去重查询、分类管理、模糊搜索、批量更新和统计等功能。
 * 继承 {@link JpaRepository}，自动获得基础的增删改查和分页能力。
 */
@Repository
public interface KnowledgeBaseRepository extends JpaRepository<KnowledgeBaseEntity, Long> {

    // ==================== 去重查询 ====================

    /**
     * 根据文件 SHA-256 哈希值查找知识库。
     * <p>
     * 上传时先用哈希检查是否已有相同内容的文件，避免重复存储和向量化。
     *
     * @param fileHash 文件内容的 SHA-256 哈希值
     * @return 匹配的知识库（Optional）
     */
    Optional<KnowledgeBaseEntity> findByFileHash(String fileHash);

    /**
     * 检查文件哈希是否已存在。
     *
     * @param fileHash 文件内容的 SHA-256 哈希值
     * @return true 表示该文件已上传过
     */
    boolean existsByFileHash(String fileHash);

    // ==================== 基础列表 ====================

    /**
     * 获取所有知识库，按上传时间倒序（最新上传的在最前）。
     */
    List<KnowledgeBaseEntity> findAllByOrderByUploadedAtDesc();

    /**
     * 按向量化状态过滤知识库，按上传时间倒序。
     *
     * @param vectorStatus 向量化状态（PENDING / PROCESSING / COMPLETED / FAILED）
     */
    List<KnowledgeBaseEntity> findByVectorStatusOrderByUploadedAtDesc(VectorStatus vectorStatus);

    // ==================== 分类管理 ====================

    /**
     * 获取所有已分配的分类名称（去重、按字母排序）。
     * 仅返回分类非空的记录。
     */
    @Query("SELECT DISTINCT k.category FROM KnowledgeBaseEntity k WHERE k.category IS NOT NULL ORDER BY k.category")
    List<String> findAllCategories();

    /**
     * 根据分类获取知识库列表，按上传时间倒序。
     *
     * @param category 分类名称
     */
    List<KnowledgeBaseEntity> findByCategoryOrderByUploadedAtDesc(String category);

    /**
     * 获取未分类（category 为 null）的知识库，按上传时间倒序。
     */
    List<KnowledgeBaseEntity> findByCategoryIsNullOrderByUploadedAtDesc();

    // ==================== 模糊搜索 ====================

    /**
     * 按知识库名称或原始文件名模糊搜索（不区分大小写）。
     *
     * @param keyword 搜索关键词
     * @return 匹配的知识库列表，按上传时间倒序
     */
    @Query("SELECT k FROM KnowledgeBaseEntity k WHERE LOWER(k.name) LIKE LOWER(CONCAT('%', :keyword, '%')) " +
           "OR LOWER(k.originalFilename) LIKE LOWER(CONCAT('%', :keyword, '%')) ORDER BY k.uploadedAt DESC")
    List<KnowledgeBaseEntity> searchByKeyword(@Param("keyword") String keyword);

    // ==================== 排序变体 ====================

    /** 按文件大小降序获取所有知识库。 */
    List<KnowledgeBaseEntity> findAllByOrderByFileSizeDesc();

    /** 按访问次数降序获取所有知识库。 */
    List<KnowledgeBaseEntity> findAllByOrderByAccessCountDesc();

    /** 按提问次数降序获取所有知识库。 */
    List<KnowledgeBaseEntity> findAllByOrderByQuestionCountDesc();

    // ==================== 批量更新 ====================

    /**
     * 批量增加知识库的提问计数（原子操作 +1）。
     * <p>
     * 使用单条 JPQL UPDATE 语句，避免 N+1 查询。
     *
     * @param ids 需要增加计数的知识库 ID 列表
     * @return 被更新的记录行数
     */
    @Modifying
    @Query("UPDATE KnowledgeBaseEntity k SET k.questionCount = k.questionCount + 1 WHERE k.id IN :ids")
    int incrementQuestionCountBatch(@Param("ids") List<Long> ids);

    // ==================== 统计查询 ====================

    /** 统计所有知识库的提问次数总和。 */
    @Query("SELECT COALESCE(SUM(k.questionCount), 0) FROM KnowledgeBaseEntity k")
    long sumQuestionCount();

    /** 统计所有知识库的访问次数总和。 */
    @Query("SELECT COALESCE(SUM(k.accessCount), 0) FROM KnowledgeBaseEntity k")
    long sumAccessCount();

    /**
     * 按向量化状态统计知识库数量。
     *
     * @param vectorStatus 向量化状态
     * @return 该状态下的知识库数量
     */
    long countByVectorStatus(VectorStatus vectorStatus);
}
