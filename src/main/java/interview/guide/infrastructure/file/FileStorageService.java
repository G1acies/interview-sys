package interview.guide.infrastructure.file;

import interview.guide.common.config.StorageConfigProperties;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

/**
 * 文件存储服务 — 封装 S3 兼容对象存储（MinIO）的所有操作。
 *
 * <h3>文件路径结构</h3>
 * 上传后的 fileKey 格式为：{@code resumes/yyyy/MM/dd/uuid8_safeName}
 * <pre>
 *   resumes/2026/06/01/a1b2c3d4_test-resume.pdf
 *   └─────┘ └─┬─┘ └──┬──┘ └───┬──┘
 *    前缀   日期路径  UUID前8位  清理后的原始文件名
 * </pre>
 *
 * <h3>配置来源</h3>
 * 从 {@link StorageConfigProperties} 读取连接参数，由 {@link S3Config} 提供客户端。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FileStorageService {

    private final S3Client s3Client;
    private final StorageConfigProperties storageConfig;

    /** 日期路径格式：年/月/日，如 2026/06/01 */
    private static final DateTimeFormatter DATE_PATH_FORMAT = DateTimeFormatter.ofPattern("yyyy/MM/dd");

    // ==================== 生命周期 ====================

    /**
     * 应用启动时自动执行：确保存储桶存在，不存在就创建。
     * 这样首次启动无需手动在 MinIO 控制台创建 bucket。
     */
    @PostConstruct
    public void init() {
        try {
            ensureBucketExists();
        } catch (Exception e) {
            // S3 服务（MinIO）不可用时不阻断启动，上传时再报错
            log.warn("S3 存储服务不可用，文件上传功能将受限: {}", e.getMessage());
        }
    }

    // ==================== 公开 API ====================

    /**
     * 上传简历文件。
     *
     * @param file MultipartFile（来自 Controller 层）
     * @return 文件在 MinIO 中的存储键（fileKey），如 resumes/2026/06/01/a1b2c3d4_xxx.pdf
     */
    public String uploadResume(MultipartFile file) {
        return uploadFile(file, "resumes");
    }

    /**
     * 删除简历文件。
     *
     * @param fileKey 上传时返回的存储键
     */
    public void deleteResume(String fileKey) {
        deleteFile(fileKey);
    }

    /**
     * 下载文件内容（字节数组）。
     * <p>
     * 适用场景：AI 解析简历时需要读取文件二进制内容。
     *
     * @param fileKey 存储键
     * @return 文件的完整字节数组
     */
    public byte[] downloadFile(String fileKey) {
        // 先检查文件是否存在，不存在直接抛异常
        if (!fileExists(fileKey)) {
            throw new BusinessException(ErrorCode.STORAGE_DOWNLOAD_FAILED, "文件不存在: " + fileKey);
        }

        try {
            // 构建 S3 GetObject 请求
            GetObjectRequest getRequest = GetObjectRequest.builder()
                    .bucket(storageConfig.getBucket())
                    .key(fileKey)
                    .build();
            // 一次性读取整个文件到内存
            return s3Client.getObjectAsBytes(getRequest).asByteArray();
        } catch (S3Exception e) {
            log.error("下载文件失败: {} - {}", fileKey, e.getMessage(), e);
            throw new BusinessException(ErrorCode.STORAGE_DOWNLOAD_FAILED, "文件下载失败: " + e.getMessage());
        }
    }

    /**
     * 根据 fileKey 生成可访问的文件 URL。
     * <p>
     * 格式：{@code endpoint/bucket/fileKey}
     * 示例：{@code http://localhost:9000/interview-guide/resumes/2026/06/01/xxx.pdf}
     */
    public String getFileUrl(String fileKey) {
        return String.format("%s/%s/%s", storageConfig.getEndpoint(), storageConfig.getBucket(), fileKey);
    }

    // ==================== 存储桶管理 ====================

    /**
     * 确保存储桶存在，不存在则创建。
     * <p>
     * 流程：先发 HEAD 请求探测 → 桶不存在（NoSuchBucketException）→ 创建
     */
    public void ensureBucketExists() {
        try {
            // HEAD 请求探测桶是否存在
            HeadBucketRequest headRequest = HeadBucketRequest.builder()
                    .bucket(storageConfig.getBucket())
                    .build();
            s3Client.headBucket(headRequest);
            log.info("存储桶已存在: {}", storageConfig.getBucket());
        } catch (NoSuchBucketException e) {
            // 桶不存在 → 创建
            log.info("存储桶不存在，正在创建: {}", storageConfig.getBucket());
            CreateBucketRequest createRequest = CreateBucketRequest.builder()
                    .bucket(storageConfig.getBucket())
                    .build();
            s3Client.createBucket(createRequest);
            log.info("存储桶创建成功: {}", storageConfig.getBucket());
        } catch (S3Exception e) {
            // 其它 S3 错误（如网络不通），记日志但不要阻断启动
            log.error("检查存储桶失败: {}", e.getMessage(), e);
        }
    }

    // ==================== 内部实现 ====================

    /**
     * 通用的文件上传逻辑。
     *
     * <h3>执行步骤</h3>
     * <ol>
     *   <li>从 MultipartFile 中取原始文件名</li>
     *   <li>生成存储键（fileKey）：前缀/日期/UUID_安全文件名</li>
     *   <li>构建 PutObjectRequest（桶、键、MIME类型、大小）</li>
     *   <li>将 MultipartFile 的输入流作为请求体发送给 S3</li>
     *   <li>返回 fileKey 供后续使用</li>
     * </ol>
     *
     * @param file   MultipartFile，Spring 自动从 multipart 请求中解析
     * @param prefix S3 对象前缀（目录名），如 "resumes"、"knowledgebases"
     * @return 文件在 MinIO 中的唯一标识键
     */
    private String uploadFile(MultipartFile file, String prefix) {
        // 1. 获取原始文件名（浏览器端文件的名字，如 "我的简历.pdf"）
        String originalFilename = file.getOriginalFilename();
        // 2. 生成带日期和 UUID 的存储键，避免同名覆盖
        String fileKey = generateFileKey(originalFilename, prefix);

        try {
            // 3. 构建 S3 上传请求
            PutObjectRequest putRequest = PutObjectRequest.builder()
                    .bucket(storageConfig.getBucket())           // 存到哪个桶
                    .key(fileKey)                                 // 文件的完整路径（键）
                    .contentType(file.getContentType())           // MIME 类型，如 application/pdf
                    .contentLength(file.getSize())                // 文件大小（字节）
                    .build();

            // 4. 执行上传：将 MultipartFile 的字节流直接写给 S3
            s3Client.putObject(putRequest, RequestBody.fromInputStream(file.getInputStream(), file.getSize()));
            log.info("文件上传成功: {} -> {}", originalFilename, fileKey);
            return fileKey;
        } catch (IOException e) {
            // 读取 MultipartFile 流失败（极少发生，通常是临时文件被删）
            log.error("读取上传文件失败: {}", e.getMessage(), e);
            throw new BusinessException(ErrorCode.STORAGE_UPLOAD_FAILED, "文件读取失败");
        } catch (S3Exception e) {
            // S3 服务端错误（网络不通、认证失败、桶不存在等）
            log.error("上传文件到S3失败: {}", e.getMessage(), e);
            throw new BusinessException(ErrorCode.STORAGE_UPLOAD_FAILED, "文件存储失败: " + e.getMessage());
        }
    }

    /**
     * 通用文件删除逻辑。
     * <p>
     * 删除前会先检查文件是否存在，避免不必要的 API 调用。
     *
     * @param fileKey 要删除的文件键，null 或空字符串直接跳过
     */
    private void deleteFile(String fileKey) {
        // 空键直接跳过（防御性编程）
        if (fileKey == null || fileKey.isEmpty()) {
            return;
        }

        // 文件不存在就跳过，不抛异常（删除已不存在的文件视作成功）
        if (!fileExists(fileKey)) {
            log.warn("文件不存在，跳过删除: {}", fileKey);
            return;
        }

        try {
            DeleteObjectRequest deleteRequest = DeleteObjectRequest.builder()
                    .bucket(storageConfig.getBucket())
                    .key(fileKey)
                    .build();
            s3Client.deleteObject(deleteRequest);
            log.info("文件删除成功: {}", fileKey);
        } catch (S3Exception e) {
            log.error("删除文件失败: {} - {}", fileKey, e.getMessage(), e);
            throw new BusinessException(ErrorCode.STORAGE_DELETE_FAILED, "文件删除失败: " + e.getMessage());
        }
    }

    /**
     * 检查文件是否存在于 MinIO 中。
     * <p>
     * 通过 HEAD 请求探测，只获取元数据不下载内容，开销极小。
     *
     * @param fileKey 文件键
     * @return true 表示文件存在
     */
    private boolean fileExists(String fileKey) {
        try {
            HeadObjectRequest headRequest = HeadObjectRequest.builder()
                    .bucket(storageConfig.getBucket())
                    .key(fileKey)
                    .build();
            s3Client.headObject(headRequest);
            return true;
        } catch (NoSuchKeyException e) {
            // 文件不存在 — 正常情况，不算错误
            return false;
        } catch (S3Exception e) {
            // 其它错误（如网络问题），记录日志但保守返回 false
            log.warn("检查文件存在性失败: {} - {}", fileKey, e.getMessage());
            return false;
        }
    }

    /**
     * 生成文件的存储键（MinIO 中的完整路径）。
     * <p>
     * 格式：{@code prefix/yyyy/MM/dd/uuid8_safeName}
     * <ul>
     *   <li><b>prefix</b> — 业务分类，如 resumes、knowledgebases</li>
     *   <li><b>日期路径</b> — 按天分目录，便于管理和清理</li>
     *   <li><b>uuid8</b> — UUID 前 8 位，防止同名文件覆盖</li>
     *   <li><b>safeName</b> — 清理后的原始文件名（去掉特殊字符）</li>
     * </ul>
     */
    private String generateFileKey(String originalFilename, String prefix) {
        LocalDateTime now = LocalDateTime.now();
        String datePath = now.format(DATE_PATH_FORMAT);               // 2026/06/01
        String uuid = UUID.randomUUID().toString().substring(0, 8);   // a1b2c3d4
        String safeName = sanitizeFilename(originalFilename);          // 我的简历.pdf → _.pdf
        return String.format("%s/%s/%s_%s", prefix, datePath, uuid, safeName);
    }

    /**
     * 清理文件名：只保留字母、数字、点号、下划线和连字符，其余全部替换为下划线。
     * <p>
     * 目的：防止文件名中的特殊字符（空格、中文等）导致 S3 兼容性问题。
     */
    private String sanitizeFilename(String filename) {
        if (filename == null || filename.isEmpty()) {
            return "unknown";
        }
        // [^...] 表示"不在集合中的字符"，全部替换为 _
        return filename.replaceAll("[^a-zA-Z0-9.\\-_]", "_");
    }
}
