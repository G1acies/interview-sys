package interview.guide.modules.knowledgebase.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.infrastructure.file.ContentTypeDetectionService;
import interview.guide.infrastructure.file.DocumentParseService;
import interview.guide.infrastructure.file.FileHashService;
import interview.guide.infrastructure.file.FileStorageService;
import interview.guide.infrastructure.file.FileValidationService;
import interview.guide.modules.knowledgebase.listener.VectorizeStreamProducer;
import interview.guide.modules.knowledgebase.model.KnowledgeBaseEntity;
import interview.guide.modules.knowledgebase.model.KnowledgeBaseListItemDTO;
import interview.guide.modules.knowledgebase.model.KnowledgeBaseStatsDTO;
import interview.guide.modules.knowledgebase.model.UploadKnowledgeBaseResponse;
import interview.guide.modules.knowledgebase.model.VectorStatus;
import interview.guide.modules.knowledgebase.repository.KnowledgeBaseRepository;
import interview.guide.modules.knowledgebase.repository.RagChatSessionRepository;
import interview.guide.modules.knowledgebase.repository.VectorRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Optional;

/**
 * 知识库管理服务。
 * <p>
 * 合并了上传、解析、持久化、列表、分类、搜索、统计、下载、删除等所有知识库管理功能。
 * RAG 查询由 {@link KnowledgeBaseQueryService} 负责，向量化由 {@link KnowledgeBaseVectorService} + 异步 Listener 处理。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeBaseManageService {

    private final FileValidationService fileValidationService;
    private final ContentTypeDetectionService contentTypeDetectionService;
    private final FileHashService fileHashService;
    private final DocumentParseService documentParseService;
    private final FileStorageService fileStorageService;
    private final KnowledgeBaseRepository knowledgeBaseRepository;
    private final RagChatSessionRepository ragChatSessionRepository;
    private final VectorRepository vectorRepository;
    private final VectorizeStreamProducer vectorizeStreamProducer;

    private static final long MAX_FILE_SIZE = 50 * 1024 * 1024; // 50MB

    // ==================================================================
    // 上传
    // ==================================================================

    /**
     * 上传知识库文件。
     * <p>
     * 完整流程：
     * <ol>
     *   <li>文件校验 — 大小、MIME 类型</li>
     *   <li>计算 SHA-256 哈希 — 用于去重</li>
     *   <li>查重 — 已存在则返回已有记录（duplicate=true）</li>
     *   <li>新文件 → Tika 解析纯文本</li>
     *   <li>上传 MinIO</li>
     *   <li>保存元数据到 knowledge_bases 表（状态 = PENDING）</li>
     *   <li>发送向量化任务到 Redis Stream（异步）</li>
     *   <li>返回上传结果</li>
     * </ol>
     */
    public UploadKnowledgeBaseResponse uploadKnowledgeBase(MultipartFile file, String name, String category) {
        // 1. 文件基本校验（是否为空、是否超 50MB）
        fileValidationService.validateFile(file, MAX_FILE_SIZE, "知识库");

        String fileName = file.getOriginalFilename();
        log.info("收到知识库上传请求: {}, 大小: {} bytes, category: {}", fileName, file.getSize(), category);

        // 2. Tika 检测真实 MIME 类型（比 HTTP Content-Type 准确）
        String contentType = contentTypeDetectionService.detectContentType(file);

        // 3. MIME 类型白名单校验（支持 PDF/DOCX/TXT/MD 等）
        fileValidationService.validateContentType(
            contentType,
            fileName,
            fileValidationService::isKnowledgeBaseMimeType,
            fileValidationService::isMarkdownExtension,
            "不支持的文件类型: " + contentType + "，支持的类型：PDF、DOCX、DOC、TXT、MD 等"
        );

        // 4. 计算 SHA-256 哈希，检查是否已存在相同文件
        String fileHash = fileHashService.calculateHash(file);
        Optional<KnowledgeBaseEntity> existingKb = knowledgeBaseRepository.findByFileHash(fileHash);
        if (existingKb.isPresent()) {
            log.info("检测到重复知识库: hash={}", fileHash);
            return handleDuplicateKnowledgeBase(existingKb.get(), fileHash);
        }

        // 5. Tika 解析文档内容（用于后续向量化）
        String content = documentParseService.parseContent(file);
        if (content == null || content.trim().isEmpty()) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "无法从文件中提取文本内容，请确保文件格式正确");
        }

        // 6. 上传文件到 MinIO
        String fileKey = fileStorageService.uploadKnowledgeBase(file);
        String fileUrl = fileStorageService.getFileUrl(fileKey);
        log.info("知识库已存储到 MinIO: {}", fileKey);

        // 7. 保存知识库元数据到数据库（状态 = PENDING）
        KnowledgeBaseEntity savedKb = saveKnowledgeBase(file, name, category, fileKey, fileUrl, fileHash);

        // 8. 发送向量化任务到 Redis Stream（异步处理，不阻塞响应）
        vectorizeStreamProducer.sendVectorizeTask(savedKb.getId(), content);

        log.info("知识库上传完成，向量化任务已入队: {}, kbId={}", fileName, savedKb.getId());

        // 9. 构建响应
        return new UploadKnowledgeBaseResponse(
            new UploadKnowledgeBaseResponse.KnowledgeBaseInfo(
                savedKb.getId(),
                savedKb.getName(),
                savedKb.getCategory() != null ? savedKb.getCategory() : "",
                savedKb.getFileSize(),
                content.length()
            ),
            new UploadKnowledgeBaseResponse.StorageInfo(fileKey, fileUrl),
            false
        );
    }

    /**
     * 重新向量化知识库（手动重试）。
     * <p>
     * 从 MinIO 重新下载文件 → 解析内容 → 发送向量化任务。
     * 用户在前端看到向量化失败后，点击"重新向量化"按钮触发此方法。
     */
    public void revectorize(Long kbId) {
        KnowledgeBaseEntity kb = knowledgeBaseRepository.findById(kbId)
            .orElseThrow(() -> new BusinessException(ErrorCode.KNOWLEDGE_BASE_NOT_FOUND, "知识库不存在"));

        log.info("开始重新向量化知识库: kbId={}, name={}", kbId, kb.getName());

        // 从 MinIO 下载文件并解析文本
        String content = documentParseService.downloadAndParseContent(fileStorageService, kb.getStorageKey(), kb.getOriginalFilename());
        if (content == null || content.trim().isEmpty()) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "无法从文件中提取文本内容");
        }

        // 重置状态为 PENDING
        updateVectorStatusToPending(kbId);

        // 发送向量化任务
        vectorizeStreamProducer.sendVectorizeTask(kbId, content);

        log.info("重新向量化任务已发送: kbId={}", kbId);
    }

    // ==================================================================
    // 解析（委托 DocumentParseService）
    // ==================================================================

    /** Tika 解析上传文件的纯文本内容。 */
    public String parseContent(MultipartFile file) {
        return documentParseService.parseContent(file);
    }

    /** Tika 解析字节数组的纯文本内容。 */
    public String parseContent(byte[] fileBytes, String fileName) {
        return documentParseService.parseContent(fileBytes, fileName);
    }

    /** 从 MinIO 下载文件后交给 Tika 解析。 */
    public String downloadAndParseContent(String storageKey, String originalFilename) {
        return documentParseService.downloadAndParseContent(fileStorageService, storageKey, originalFilename);
    }

    /** 检测文件的 MIME 类型。 */
    public String detectContentType(MultipartFile file) {
        return contentTypeDetectionService.detectContentType(file);
    }

    // ==================================================================
    // 持久化（事务性 DB 操作）
    // ==================================================================

    /**
     * 处理重复知识库。
     * <p>
     * 文件哈希已存在时，只增加访问计数，不重复存储和向量化。
     */
    @Transactional(rollbackFor = Exception.class)
    public UploadKnowledgeBaseResponse handleDuplicateKnowledgeBase(KnowledgeBaseEntity kb, String fileHash) {
        log.info("检测到重复知识库，返回已有记录: kbId={}", kb.getId());

        kb.incrementAccessCount();
        knowledgeBaseRepository.save(kb);

        return new UploadKnowledgeBaseResponse(
            new UploadKnowledgeBaseResponse.KnowledgeBaseInfo(
                kb.getId(),
                kb.getName(),
                kb.getCategory() != null ? kb.getCategory() : "",
                kb.getFileSize(),
                0
            ),
            new UploadKnowledgeBaseResponse.StorageInfo(
                kb.getStorageKey() != null ? kb.getStorageKey() : "",
                kb.getStorageUrl() != null ? kb.getStorageUrl() : ""
            ),
            true
        );
    }

    /**
     * 保存新知识库元数据到数据库。
     */
    @Transactional(rollbackFor = Exception.class)
    public KnowledgeBaseEntity saveKnowledgeBase(MultipartFile file, String name, String category,
                                                  String storageKey, String storageUrl, String fileHash) {
        KnowledgeBaseEntity kb = new KnowledgeBaseEntity();
        kb.setFileHash(fileHash);
        kb.setName(name != null && !name.trim().isEmpty() ? name : extractNameFromFilename(file.getOriginalFilename()));
        kb.setCategory(category != null && !category.trim().isEmpty() ? category.trim() : null);
        kb.setOriginalFilename(file.getOriginalFilename());
        kb.setFileSize(file.getSize());
        kb.setContentType(file.getContentType());
        kb.setStorageKey(storageKey);
        kb.setStorageUrl(storageUrl);

        KnowledgeBaseEntity saved = knowledgeBaseRepository.save(kb);
        log.info("知识库已保存: id={}, name={}, category={}", saved.getId(), saved.getName(), saved.getCategory());
        return saved;
    }

    /** 更新知识库向量化状态为 PENDING（用于手动重试时重置）。 */
    @Transactional(rollbackFor = Exception.class)
    public void updateVectorStatusToPending(Long kbId) {
        KnowledgeBaseEntity kb = knowledgeBaseRepository.findById(kbId)
            .orElseThrow(() -> new BusinessException(ErrorCode.KNOWLEDGE_BASE_NOT_FOUND, "知识库不存在"));
        kb.setVectorStatus(VectorStatus.PENDING);
        kb.setVectorError(null);
        knowledgeBaseRepository.save(kb);
    }

    /** 从文件名中提取知识库名称（去掉扩展名）。 */
    private String extractNameFromFilename(String filename) {
        if (filename == null || filename.isEmpty()) return "未命名知识库";
        int lastDot = filename.lastIndexOf('.');
        return lastDot > 0 ? filename.substring(0, lastDot) : filename;
    }

    // ==================================================================
    // 列表与详情
    // ==================================================================

    /**
     * 获取知识库列表（支持状态过滤和排序）。
     * <p>
     * 如果指定了 vectorStatus，按状态过滤；否则返回全部。
     * 排序字段支持：time（默认）、size、access、question。
     */
    public List<KnowledgeBaseListItemDTO> listKnowledgeBases(VectorStatus vectorStatus, String sortBy) {
        List<KnowledgeBaseEntity> entities;

        if (vectorStatus != null) {
            entities = knowledgeBaseRepository.findByVectorStatusOrderByUploadedAtDesc(vectorStatus);
        } else {
            entities = knowledgeBaseRepository.findAllByOrderByUploadedAtDesc();
        }

        // 非时间排序在内存中做（数据量小，单次查询）
        if (sortBy != null && !sortBy.isBlank() && !sortBy.equalsIgnoreCase("time")) {
            entities = sortEntities(entities, sortBy);
        }

        return entities.stream().map(this::toListItemDTO).toList();
    }

    /** 所有知识库列表（默认按上传时间倒序）。 */
    public List<KnowledgeBaseListItemDTO> listKnowledgeBases() {
        return listKnowledgeBases(null, null);
    }

    /** 按向量化状态获取列表。 */
    public List<KnowledgeBaseListItemDTO> listKnowledgeBasesByStatus(VectorStatus vectorStatus) {
        return listKnowledgeBases(vectorStatus, null);
    }

    /**
     * 获取知识库详情。
     *
     * @param id 知识库 ID
     * @return Optional 为空表示不存在
     */
    public Optional<KnowledgeBaseListItemDTO> getKnowledgeBase(Long id) {
        return knowledgeBaseRepository.findById(id).map(this::toListItemDTO);
    }

    /** 获取知识库实体（内部使用，如删除前校验）。 */
    public Optional<KnowledgeBaseEntity> getKnowledgeBaseEntity(Long id) {
        return knowledgeBaseRepository.findById(id);
    }

    /**
     * 批量获取知识库名称（用于 RAG 查询响应中展示）。
     * 不存在的知识库返回 "未知知识库"。
     */
    public List<String> getKnowledgeBaseNames(List<Long> ids) {
        return ids.stream()
            .map(id -> knowledgeBaseRepository.findById(id)
                .map(KnowledgeBaseEntity::getName)
                .orElse("未知知识库"))
            .toList();
    }

    /** 在内存中对实体列表排序。 */
    private List<KnowledgeBaseEntity> sortEntities(List<KnowledgeBaseEntity> entities, String sortBy) {
        return switch (sortBy.toLowerCase()) {
            case "size" -> entities.stream()
                .sorted((a, b) -> Long.compare(b.getFileSize(), a.getFileSize()))
                .toList();
            case "access" -> entities.stream()
                .sorted((a, b) -> Integer.compare(b.getAccessCount(), a.getAccessCount()))
                .toList();
            case "question" -> entities.stream()
                .sorted((a, b) -> Integer.compare(b.getQuestionCount(), a.getQuestionCount()))
                .toList();
            default -> entities; // time 已在 DB 层面排序
        };
    }

    /** Entity → DTO 转换。 */
    private KnowledgeBaseListItemDTO toListItemDTO(KnowledgeBaseEntity e) {
        return new KnowledgeBaseListItemDTO(
            e.getId(),
            e.getName(),
            e.getCategory(),
            e.getOriginalFilename(),
            e.getFileSize(),
            e.getContentType(),
            e.getUploadedAt(),
            e.getLastAccessedAt(),
            e.getAccessCount(),
            e.getQuestionCount(),
            e.getVectorStatus(),
            e.getVectorError(),
            e.getChunkCount()
        );
    }

    // ==================================================================
    // 分类管理
    // ==================================================================

    /**
     * 获取所有已分配的分类名称（去重、按字母排序）。
     * 不含未分类（category = null）的记录。
     */
    public List<String> getAllCategories() {
        return knowledgeBaseRepository.findAllCategories();
    }

    /**
     * 根据分类获取知识库列表。
     *
     * @param category 分类名称，传 null 表示获取未分类的记录
     */
    public List<KnowledgeBaseListItemDTO> listByCategory(String category) {
        List<KnowledgeBaseEntity> entities;
        if (category == null || category.isBlank()) {
            entities = knowledgeBaseRepository.findByCategoryIsNullOrderByUploadedAtDesc();
        } else {
            entities = knowledgeBaseRepository.findByCategoryOrderByUploadedAtDesc(category);
        }
        return entities.stream().map(this::toListItemDTO).toList();
    }

    /**
     * 更新知识库分类。
     *
     * @param id       知识库 ID
     * @param category 新分类名称，传 null 或空字符串表示设为未分类
     */
    @Transactional
    public void updateCategory(Long id, String category) {
        KnowledgeBaseEntity entity = knowledgeBaseRepository.findById(id)
            .orElseThrow(() -> new BusinessException(ErrorCode.KNOWLEDGE_BASE_NOT_FOUND, "知识库不存在"));
        entity.setCategory(category != null && !category.isBlank() ? category : null);
        knowledgeBaseRepository.save(entity);
        log.info("更新知识库分类: id={}, category={}", id, category);
    }

    // ==================================================================
    // 搜索
    // ==================================================================

    /**
     * 按关键词搜索知识库（匹配名称或原始文件名，不区分大小写）。
     */
    public List<KnowledgeBaseListItemDTO> search(String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return listKnowledgeBases();
        }
        return knowledgeBaseRepository.searchByKeyword(keyword.trim())
            .stream()
            .map(this::toListItemDTO)
            .toList();
    }

    // ==================================================================
    // 统计
    // ==================================================================

    /**
     * 获取知识库统计信息。
     * <p>
     * 指标说明：
     * <ul>
     *   <li>totalCount — 知识库文档总数</li>
     *   <li>totalQuestionCount — 所有知识库累计提问次数（来自 knowledge_bases.question_count 汇总）</li>
     *   <li>totalAccessCount — 所有知识库累计访问次数</li>
     *   <li>completedCount — 向量化完成的知识库数量（vectorStatus = COMPLETED）</li>
     *   <li>processingCount — 向量化处理中的知识库数量（vectorStatus = PROCESSING）</li>
     * </ul>
     */
    public KnowledgeBaseStatsDTO getStatistics() {
        return new KnowledgeBaseStatsDTO(
            knowledgeBaseRepository.count(),
            knowledgeBaseRepository.sumQuestionCount(),
            knowledgeBaseRepository.sumAccessCount(),
            knowledgeBaseRepository.countByVectorStatus(VectorStatus.COMPLETED),
            knowledgeBaseRepository.countByVectorStatus(VectorStatus.PROCESSING)
        );
    }

    // ==================================================================
    // 下载
    // ==================================================================

    /**
     * 从 MinIO 下载知识库的原始文件。
     *
     * @param id 知识库 ID
     * @return 文件字节数组
     */
    public byte[] downloadFile(Long id) {
        KnowledgeBaseEntity entity = knowledgeBaseRepository.findById(id)
            .orElseThrow(() -> new BusinessException(ErrorCode.KNOWLEDGE_BASE_NOT_FOUND, "知识库不存在"));
        String storageKey = entity.getStorageKey();
        if (storageKey == null || storageKey.isBlank()) {
            throw new BusinessException(ErrorCode.STORAGE_DOWNLOAD_FAILED, "文件存储信息不存在");
        }
        return fileStorageService.downloadFile(storageKey);
    }

    /**
     * 获取知识库实体（用于下载时的文件名和 Content-Type）。
     *
     * @param id 知识库 ID
     * @return 知识库实体
     */
    public KnowledgeBaseEntity getEntityForDownload(Long id) {
        return knowledgeBaseRepository.findById(id)
            .orElseThrow(() -> new BusinessException(ErrorCode.KNOWLEDGE_BASE_NOT_FOUND, "知识库不存在"));
    }

    // ==================================================================
    // 计数
    // ==================================================================
    @Transactional(rollbackFor = Exception.class)
    public void updateQuestionCounts(List<Long> knowledgeBaseIds) {} // TODO

    // ==================================================================
    // 删除
    // ==================================================================

    /**
     * 级联删除知识库。
     * <p>
     * 严格按照以下顺序执行，每一步独立 try-catch 避免单步失败阻断后续：
     * <ol>
     *   <li>从所有 RAG 会话中移除该知识库的关联（否则外键约束阻止删除）</li>
     *   <li>删除 pgvector 中的向量数据</li>
     *   <li>删除 MinIO 中的原始文件</li>
     *   <li>删除 knowledge_bases 表中的记录</li>
     * </ol>
     */
    @Transactional(rollbackFor = Exception.class)
    public void deleteKnowledgeBase(Long id) {
        KnowledgeBaseEntity kb = knowledgeBaseRepository.findById(id)
            .orElseThrow(() -> new BusinessException(ErrorCode.KNOWLEDGE_BASE_NOT_FOUND, "知识库不存在"));

        log.info("开始删除知识库: id={}, name={}", id, kb.getName());

        // 1. 从 RAG 会话中移除关联
        var sessions = ragChatSessionRepository.findByKnowledgeBaseIds(List.of(id));
        for (var session : sessions) {
            session.getKnowledgeBases().removeIf(k -> k.getId().equals(id));
            ragChatSessionRepository.save(session);
        }
        if (!sessions.isEmpty()) {
            log.info("已从 {} 个会话中移除知识库关联: kbId={}", sessions.size(), id);
        }

        // 2. 删除向量数据（失败不阻断）
        try {
            vectorRepository.deleteByKnowledgeBaseId(id);
        } catch (Exception e) {
            log.warn("删除向量数据失败，继续执行: kbId={}, error={}", id, e.getMessage());
        }

        // 3. 删除 MinIO 文件（失败不阻断）
        try {
            fileStorageService.deleteKnowledgeBase(kb.getStorageKey());
        } catch (Exception e) {
            log.warn("删除 MinIO 文件失败，继续执行: kbId={}, error={}", id, e.getMessage());
        }

        // 4. 删除数据库记录
        knowledgeBaseRepository.deleteById(id);
        log.info("知识库已删除: id={}", id);
    }
}
