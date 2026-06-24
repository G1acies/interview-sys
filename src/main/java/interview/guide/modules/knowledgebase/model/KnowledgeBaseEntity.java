package interview.guide.modules.knowledgebase.model;

import jakarta.persistence.*;

import java.time.LocalDateTime;

/**
 * 知识库文档实体。
 * <p>
 * 对应数据库 {@code knowledge_bases} 表，记录每一份上传文档的元数据。
 * 文档上传后将经历"上传 → 向量化 → 可检索"的生命周期，状态由 {@link VectorStatus} 追踪。
 * <p>
 * <b>核心字段说明：</b>
 * <ul>
 *   <li>{@code fileHash} — 文件内容的 SHA-256，用于上传去重</li>
 *   <li>{@code storageKey} — 文件在 MinIO（S3 协议）中的存储路径</li>
 *   <li>{@code vectorStatus} — 当前向量化进度状态</li>
 *   <li>{@code category} — 用户自定义分类（如"Java 面试"、"项目文档"），可为 null</li>
 * </ul>
 * <p>
 * <b>索引：</b>
 * <ul>
 *   <li>{@code fileHash} — 唯一索引，确保哈希级别的去重</li>
 *   <li>{@code category} — 普通索引，加速按分类查询</li>
 * </ul>
 */
@Entity
@Table(name = "knowledge_bases", indexes = {
    @Index(name = "idx_kb_hash", columnList = "fileHash", unique = true),
    @Index(name = "idx_kb_category", columnList = "category")
})
public class KnowledgeBaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 文件内容的 SHA-256 哈希值，用于上传去重。唯一约束。 */
    @Column(nullable = false, unique = true, length = 64)
    private String fileHash;

    /** 知识库名称（用户自定义，为空时从文件名自动提取）。 */
    @Column(nullable = false)
    private String name;

    /** 分类/分组标签（如 "Java面试"、"项目文档"）。可为 null，表示未分类。 */
    @Column(length = 100)
    private String category;

    /** 用户上传时的原始文件名（用于下载时还原文件名）。 */
    @Column(nullable = false)
    private String originalFilename;

    /** 文件大小（字节）。 */
    private Long fileSize;

    /** 文件的 MIME 类型（如 application/pdf）。 */
    private String contentType;

    /** 文件在 MinIO（S3 协议）中的存储键。 */
    @Column(length = 500)
    private String storageKey;

    /** 文件的 S3 访问 URL。 */
    @Column(length = 1000)
    private String storageUrl;

    /** 上传时间。由 {@code @PrePersist} 自动设置。 */
    @Column(nullable = false)
    private LocalDateTime uploadedAt;

    /** 最后访问时间。每次访问时更新。 */
    private LocalDateTime lastAccessedAt;

    /** 访问次数。上传时初始化为 1，每次重复上传命中 +1。 */
    private Integer accessCount = 0;

    /** 用户针对此知识库提问的累计次数。 */
    private Integer questionCount = 0;

    /** 向量化状态。新上传时默认为 {@link VectorStatus#PENDING}。 */
    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private VectorStatus vectorStatus = VectorStatus.PENDING;

    /** 向量化失败时的错误信息。成功时为 null。 */
    @Column(length = 500)
    private String vectorError;

    /** 向量化分块数量（文本被切成了多少个 chunk）。0 表示尚未完成。 */
    private Integer chunkCount = 0;

    // ========== 生命周期回调 ==========

    @PrePersist
    protected void onCreate() {
        uploadedAt = LocalDateTime.now();
        lastAccessedAt = LocalDateTime.now();
        accessCount = 1;
    }

    // ========== 便捷方法 ==========

    /** 访问次数 +1，同时刷新最后访问时间。 */
    public void incrementAccessCount() {
        this.accessCount++;
        this.lastAccessedAt = LocalDateTime.now();
    }

    /** 提问次数 +1，同时刷新最后访问时间。 */
    public void incrementQuestionCount() {
        this.questionCount++;
        this.lastAccessedAt = LocalDateTime.now();
    }

    // ========== Getters & Setters ==========

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getFileHash() { return fileHash; }
    public void setFileHash(String fileHash) { this.fileHash = fileHash; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }

    public String getOriginalFilename() { return originalFilename; }
    public void setOriginalFilename(String originalFilename) { this.originalFilename = originalFilename; }

    public Long getFileSize() { return fileSize; }
    public void setFileSize(Long fileSize) { this.fileSize = fileSize; }

    public String getContentType() { return contentType; }
    public void setContentType(String contentType) { this.contentType = contentType; }

    public String getStorageKey() { return storageKey; }
    public void setStorageKey(String storageKey) { this.storageKey = storageKey; }

    public String getStorageUrl() { return storageUrl; }
    public void setStorageUrl(String storageUrl) { this.storageUrl = storageUrl; }

    public LocalDateTime getUploadedAt() { return uploadedAt; }
    public void setUploadedAt(LocalDateTime uploadedAt) { this.uploadedAt = uploadedAt; }

    public LocalDateTime getLastAccessedAt() { return lastAccessedAt; }
    public void setLastAccessedAt(LocalDateTime lastAccessedAt) { this.lastAccessedAt = lastAccessedAt; }

    public Integer getAccessCount() { return accessCount; }
    public void setAccessCount(Integer accessCount) { this.accessCount = accessCount; }

    public Integer getQuestionCount() { return questionCount; }
    public void setQuestionCount(Integer questionCount) { this.questionCount = questionCount; }

    public VectorStatus getVectorStatus() { return vectorStatus; }
    public void setVectorStatus(VectorStatus vectorStatus) { this.vectorStatus = vectorStatus; }

    public String getVectorError() { return vectorError; }
    public void setVectorError(String vectorError) { this.vectorError = vectorError; }

    public Integer getChunkCount() { return chunkCount; }
    public void setChunkCount(Integer chunkCount) { this.chunkCount = chunkCount; }
}
