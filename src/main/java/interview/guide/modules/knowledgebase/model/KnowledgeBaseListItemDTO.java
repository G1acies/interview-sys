package interview.guide.modules.knowledgebase.model;

import java.time.LocalDateTime;

/**
 * 知识库列表项 DTO。
 * <p>
 * 用于前端知识库列表页面的展示数据。
 * 由 {@link interview.guide.infrastructure.mapper.KnowledgeBaseMapper} 从
 * {@link KnowledgeBaseEntity} 转换而来。
 * <p>
 * 使用 Java {@code record} 确保不可变性，所有字段只读。
 *
 * @param id               知识库 ID
 * @param name             知识库名称
 * @param category         分类（可为 null）
 * @param originalFilename 原始文件名
 * @param fileSize         文件大小（字节）
 * @param contentType      文件 MIME 类型
 * @param uploadedAt       上传时间
 * @param lastAccessedAt   最后访问时间
 * @param accessCount      访问次数
 * @param questionCount    提问次数
 * @param vectorStatus     向量化状态
 * @param vectorError      向量化错误信息（失败时有值）
 * @param chunkCount       向量分块数量
 */
public record KnowledgeBaseListItemDTO(
    Long id,
    String name,
    String category,
    String originalFilename,
    Long fileSize,
    String contentType,
    LocalDateTime uploadedAt,
    LocalDateTime lastAccessedAt,
    Integer accessCount,
    Integer questionCount,
    VectorStatus vectorStatus,
    String vectorError,
    Integer chunkCount
) {
}
