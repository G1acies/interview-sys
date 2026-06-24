package interview.guide.modules.knowledgebase.model;

/**
 * 知识库上传响应 DTO。
 * <p>
 * 上传成功后返回给前端的信息，包含知识库元数据、存储信息和重复标记。
 * 前端根据 {@code duplicate} 字段判断是否提示"已存在相同文件"。
 *
 * @param knowledgeBase 知识库元数据
 * @param storage       文件存储信息
 * @param duplicate     是否与已有文件内容重复
 */
public record UploadKnowledgeBaseResponse(
    KnowledgeBaseInfo knowledgeBase,
    StorageInfo storage,
    boolean duplicate
) {
    /**
     * 知识库元数据。
     *
     * @param id            知识库 ID
     * @param name          知识库名称
     * @param category      分类（空字符串表示未分类）
     * @param fileSize      文件大小（字节）
     * @param contentLength 解析后的文本长度
     */
    public record KnowledgeBaseInfo(
        Long id,
        String name,
        String category,
        Long fileSize,
        int contentLength
    ) {}

    /**
     * 文件存储信息。
     *
     * @param fileKey MinIO 存储键
     * @param fileUrl 文件访问 URL
     */
    public record StorageInfo(
        String fileKey,
        String fileUrl
    ) {}
}
