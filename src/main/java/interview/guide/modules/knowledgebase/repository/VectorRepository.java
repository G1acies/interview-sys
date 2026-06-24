package interview.guide.modules.knowledgebase.repository;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * 向量数据的 Repository。
 * <p>
 * 与其它 Repository 不同，此类不继承 JpaRepository，而是直接使用 {@link JdbcTemplate}
 * 操作 Spring AI PgVectorStore 默认创建的 {@code vector_store} 表。
 * <p>
 * 使用场景：删除知识库时，需要同步清理该知识库在 pgvector 中的所有向量记录。
 * 由于 PgVectorStore 本身不提供按 metadata 字段删除的 API，必须通过原生 SQL 实现。
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class VectorRepository {

    private final JdbcTemplate jdbcTemplate;

    /**
     * 删除指定知识库的所有向量数据。
     * <p>
     * Spring AI PgVectorStore 默认表名为 {@code vector_store}，
     * 文档的元数据以 JSON 格式存储在 {@code metadata} 字段中。
     * <p>
     * 本方法通过 {@code metadata->>'kb_id'} 字段匹配知识库 ID 来删除对应向量。
     * 同时兼容 {@code kb_id_long} 字段（旧版本 PgVectorStore 可能使用此字段名）。
     * <p>
     * <b>SQL 实现注意：</b>
     * <ul>
     *   <li>metadata 是 json 类型，使用 {@code ->>} 运算符提取文本值</li>
     *   <li>避免使用 PostgreSQL 的 {@code ?} 运算符（在 JDBC 中与占位符冲突）</li>
     *   <li>OR 条件同时覆盖 {@code kb_id}（String）和 {@code kb_id_long}（BigInt）两种存储方式</li>
     * </ul>
     *
     * @param knowledgeBaseId 知识库 ID
     * @return 被删除的向量记录行数
     * @throws BusinessException 删除失败时抛出 KNOWLEDGE_BASE_DELETE_FAILED
     */
    @Transactional(rollbackFor = Exception.class)
    public int deleteByKnowledgeBaseId(Long knowledgeBaseId) {
        log.info("开始删除知识库向量数据: kbId={}", knowledgeBaseId);

        String sql = """
            DELETE FROM vector_store
            WHERE metadata->>'kb_id' = ?
               OR (metadata->>'kb_id_long' IS NOT NULL AND (metadata->>'kb_id_long')::bigint = ?)
            """;

        try {
            int deletedRows = jdbcTemplate.update(sql, knowledgeBaseId.toString(), knowledgeBaseId);

            if (deletedRows > 0) {
                log.info("成功删除知识库向量数据: kbId={}, 删除行数={}", knowledgeBaseId, deletedRows);
            } else {
                log.info("未找到相关向量数据，无需删除: kbId={}", knowledgeBaseId);
            }

            return deletedRows;

        } catch (Exception e) {
            log.error("执行删除向量 SQL 失败: kbId={}, error={}", knowledgeBaseId, e.getMessage());
            throw new BusinessException(ErrorCode.KNOWLEDGE_BASE_DELETE_FAILED, "删除向量数据失败");
        }
    }
}
