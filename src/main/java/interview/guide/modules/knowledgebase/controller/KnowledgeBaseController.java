package interview.guide.modules.knowledgebase.controller;

import interview.guide.common.exception.ErrorCode;
import interview.guide.common.result.Result;
import interview.guide.modules.knowledgebase.model.*;
import interview.guide.modules.knowledgebase.service.KnowledgeBaseManageService;
import interview.guide.modules.knowledgebase.service.KnowledgeBaseQueryService;
import jakarta.validation.Valid;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import reactor.core.publisher.Flux;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * 知识库管理控制器。
 * <p>
 * 提供知识库的完整 REST API，包括：
 * <ul>
 *   <li>文件上传 / 下载 / 删除</li>
 *   <li>知识库列表、搜索、分类管理</li>
 *   <li>RAG 查询（同步 + 流式 SSE）</li>
 *   <li>向量化手动重试</li>
 *   <li>统计信息</li>
 * </ul>
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@Tag(name = "知识库管理", description = "知识库上传、下载、查询、分类与向量化")
public class KnowledgeBaseController {

    private final KnowledgeBaseManageService manageService;
    private final KnowledgeBaseQueryService queryService;

    // ========== 列表与详情 ==========

    /**
     * 获取所有知识库列表。
     *
     * @param sortBy       排序字段：time / size / access / question
     * @param vectorStatus 向量化状态过滤：PENDING / PROCESSING / COMPLETED / FAILED
     */
    @GetMapping("/api/knowledgebase/list")
    public Result<List<KnowledgeBaseListItemDTO>> getAllKnowledgeBases(
            @RequestParam(value = "sortBy", required = false) String sortBy,
            @RequestParam(value = "vectorStatus", required = false) String vectorStatus) {
        VectorStatus status = null;
        if (vectorStatus != null && !vectorStatus.isBlank()) {
            try {
                status = VectorStatus.valueOf(vectorStatus.toUpperCase());
            } catch (IllegalArgumentException e) {
                return Result.error("无效的向量化状态: " + vectorStatus);
            }
        }
        return Result.success(manageService.listKnowledgeBases(status, sortBy));
    }

    /** 获取知识库详情。 */
    @GetMapping("/api/knowledgebase/{id}")
    public Result<KnowledgeBaseListItemDTO> getKnowledgeBase(@PathVariable Long id) {
        return manageService.getKnowledgeBase(id)
                .map(Result::success)
                .orElse(Result.error(ErrorCode.KNOWLEDGE_BASE_NOT_FOUND.getCode(), "知识库不存在"));
    }

    /** 删除知识库。 */
    @DeleteMapping("/api/knowledgebase/{id}")
    public Result<Void> deleteKnowledgeBase(@PathVariable Long id) {
        manageService.deleteKnowledgeBase(id);
        return Result.success(null);
    }

    // ========== RAG 查询 ==========

    /**
     * 基于知识库回答问题（同步，支持多知识库）。
     * 受限流保护：全局每分钟 10 次，每 IP 每分钟 10 次。
     */
    @PostMapping("/api/knowledgebase/query")
    public Result<QueryResponse> queryKnowledgeBase(@Valid @RequestBody QueryRequest request) {
        log.info("RAG 查询（非流式） knowledgeBaseIds = {}, question = {}",request.knowledgeBaseIds(), request.question());
        return Result.success (queryService.queryKnowledgeBase(request));
    }

    /**
     * 基于知识库回答问题（流式 SSE，支持多知识库）。
     * <p>
     * ┌─────────────────────────────────────────────────────────────┐
     * │ 响应格式：Server-Sent Events (SSE)                          │
     * ├─────────────────────────────────────────────────────────────┤
     * │ data:回答内容chunk1                                         │
     * │ data:回答内容chunk2                                         │
     * │ data:回答内容chunk3                                         │
     * │ ...                                                         │
     * │ 前端通过 fetch + ReadableStream 逐块消费                    │
     * └─────────────────────────────────────────────────────────────┘
     * <p>
     * 内部调用 {@link KnowledgeBaseQueryService#answerQuestionStream}，
     * 包含 Query Rewrite → 向量检索 → LLM 流式生成 → 探测窗口截断。
     *
     * @param request 查询请求（知识库 ID 列表 + 问题）
     * @return SSE 流式响应，每行 data: 后跟一个文本块
     */
    @PostMapping(value = "/api/knowledgebase/query/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> queryKnowledgeBaseStream(@Valid @RequestBody QueryRequest request) {
        log.info("收到知识库流式查询请求: kbIds={}, question={}",
            request.knowledgeBaseIds(), request.question());
        return queryService.answerQuestionStream(request.knowledgeBaseIds(), request.question());
    }

    // ========== 下载 ==========

    /**
     * 下载知识库文件。
     * <p>
     * 不走 Result 包装，直接返回文件二进制流。
     * Content-Disposition 设为 attachment 触发浏览器下载，
     * 文件名经过 URL 编码兼容中文。
     */
    @GetMapping("/api/knowledgebase/{id}/download")
    public ResponseEntity<byte[]> downloadKnowledgeBase(@PathVariable Long id) {
        var entity = manageService.getEntityForDownload(id);
        byte[] fileContent = manageService.downloadFile(id);

        String filename = entity.getOriginalFilename();
        String encodedFilename = URLEncoder.encode(filename, StandardCharsets.UTF_8)
                .replaceAll("\\+", "%20");

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + encodedFilename
                        + "\"; filename*=UTF-8''" + encodedFilename)
                .header(HttpHeaders.CONTENT_TYPE,
                        entity.getContentType() != null ? entity.getContentType()
                                : MediaType.APPLICATION_OCTET_STREAM_VALUE)
                .body(fileContent);
    }

    // ========== 分类管理 ==========

    /** 获取所有已分配的分类名称。 */
    @GetMapping("/api/knowledgebase/categories")
    public Result<List<String>> getAllCategories() {
        return Result.success(manageService.getAllCategories());
    }

    /** 根据分类获取知识库列表。 */
    @GetMapping("/api/knowledgebase/category/{category}")
    public Result<List<KnowledgeBaseListItemDTO>> getByCategory(@PathVariable String category) {
        return Result.success(manageService.listByCategory(category));
    }

    /** 获取未分类的知识库（category 为 null 的记录）。 */
    @GetMapping("/api/knowledgebase/uncategorized")
    public Result<List<KnowledgeBaseListItemDTO>> getUncategorized() {
        return Result.success(manageService.listByCategory(null));
    }

    /** 更新知识库分类。category 传 null 或空字符串表示设为未分类。 */
    @PutMapping("/api/knowledgebase/{id}/category")
    public Result<Void> updateCategory(@PathVariable Long id, @RequestBody Map<String, String> body) {
        manageService.updateCategory(id, body.get("category"));
        return Result.success(null);
    }

    // ========== 上传 ==========

    /**
     * 上传知识库文件。
     * 受限流保护：全局每分钟 3 次，每 IP 每分钟 3 次。
     */
    @PostMapping(value = "/api/knowledgebase/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Result<UploadKnowledgeBaseResponse> uploadKnowledgeBase(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "name", required = false) String name,
            @RequestParam(value = "category", required = false) String category) {
        UploadKnowledgeBaseResponse response = manageService.uploadKnowledgeBase(file, name, category);
        return Result.success(response);
    }

    // ========== 搜索 ==========

    /** 按关键词搜索知识库（匹配名称或原始文件名，不区分大小写）。 */
    @GetMapping("/api/knowledgebase/search")
    public Result<List<KnowledgeBaseListItemDTO>> search(@RequestParam("keyword") String keyword) {
        return Result.success(manageService.search(keyword));
    }

    // ========== 统计 ==========

    /**
     * 获取知识库统计信息。
     * <p>
     * 返回总数、总提问次数、总访问次数、向量化完成数和处理中数，
     * 用于前端知识库管理页面的统计概览卡片。
     */
    @GetMapping("/api/knowledgebase/stats")
    public Result<KnowledgeBaseStatsDTO> getStatistics() {
        return Result.success(manageService.getStatistics());
    }

    // ========== 向量化管理 ==========

    /**
     * 重新向量化知识库（手动重试）。
     * <p>
     * 用户在前端看到向量化状态为 FAILED 后，点击"重新向量化"按钮触发此接口。
     * 后端重新从 MinIO 下载文件 → 解析文本 → 发送向量化任务到 Redis Stream。
     */
    @PostMapping("/api/knowledgebase/{id}/revectorize")
    public Result<Void> revectorize(@PathVariable Long id) {
        manageService.revectorize(id);
        return Result.success(null);
    }
}
