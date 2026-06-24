package interview.guide.modules.knowledgebase.controller;

import interview.guide.common.result.Result;
import interview.guide.modules.knowledgebase.model.KnowledgeBaseListItemDTO;
import interview.guide.modules.knowledgebase.model.KnowledgeBaseStatsDTO;
import interview.guide.modules.knowledgebase.model.QueryRequest;
import interview.guide.modules.knowledgebase.model.QueryResponse;
import interview.guide.modules.knowledgebase.service.KnowledgeBaseDeleteService;
import interview.guide.modules.knowledgebase.service.KnowledgeBaseListService;
import interview.guide.modules.knowledgebase.service.KnowledgeBaseQueryService;
import interview.guide.modules.knowledgebase.service.KnowledgeBaseUploadService;
import jakarta.validation.Valid;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import reactor.core.publisher.Flux;

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

    private final KnowledgeBaseUploadService uploadService;
    private final KnowledgeBaseQueryService queryService;
    private final KnowledgeBaseListService listService;
    private final KnowledgeBaseDeleteService deleteService;

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
        return Result.success(List.of()); // TODO
    }

    /** 获取知识库详情。 */
    @GetMapping("/api/knowledgebase/{id}")
    public Result<KnowledgeBaseListItemDTO> getKnowledgeBase(@PathVariable Long id) {
        return Result.success(null); // TODO
    }

    /** 删除知识库。 */
    @DeleteMapping("/api/knowledgebase/{id}")
    public Result<Void> deleteKnowledgeBase(@PathVariable Long id) {
        return Result.success(null); // TODO
    }

    // ========== RAG 查询 ==========

    /**
     * 基于知识库回答问题（同步，支持多知识库）。
     * 受限流保护：全局每分钟 10 次，每 IP 每分钟 10 次。
     */
    @PostMapping("/api/knowledgebase/query")
    public Result<QueryResponse> queryKnowledgeBase(@Valid @RequestBody QueryRequest request) {
        return Result.success(null); // TODO
    }

    /**
     * 基于知识库回答问题（流式 SSE，支持多知识库）。
     * 受限流保护：全局每分钟 5 次，每 IP 每分钟 5 次。
     */
    @PostMapping(value = "/api/knowledgebase/query/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> queryKnowledgeBaseStream(@Valid @RequestBody QueryRequest request) {
        return Flux.empty(); // TODO
    }

    // ========== 分类管理 ==========

    /** 获取所有分类。 */
    @GetMapping("/api/knowledgebase/categories")
    public Result<List<String>> getAllCategories() {
        return Result.success(List.of()); // TODO
    }

    /** 根据分类获取知识库列表。 */
    @GetMapping("/api/knowledgebase/category/{category}")
    public Result<List<KnowledgeBaseListItemDTO>> getByCategory(@PathVariable String category) {
        return Result.success(List.of()); // TODO
    }

    /** 获取未分类的知识库。 */
    @GetMapping("/api/knowledgebase/uncategorized")
    public Result<List<KnowledgeBaseListItemDTO>> getUncategorized() {
        return Result.success(List.of()); // TODO
    }

    /** 更新知识库分类。 */
    @PutMapping("/api/knowledgebase/{id}/category")
    public Result<Void> updateCategory(@PathVariable Long id, @RequestBody Map<String, String> body) {
        return Result.success(null); // TODO
    }

    // ========== 上传下载 ==========

    /**
     * 上传知识库文件。
     * 受限流保护：全局每分钟 3 次，每 IP 每分钟 3 次。
     */
    @PostMapping(value = "/api/knowledgebase/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Result<Map<String, Object>> uploadKnowledgeBase(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "name", required = false) String name,
            @RequestParam(value = "category", required = false) String category) {
        return Result.success(Map.of()); // TODO
    }

    /**
     * 下载知识库文件。
     * 返回原始文件字节流，自动设置 Content-Disposition 和 Content-Type 头部。
     */
    @GetMapping("/api/knowledgebase/{id}/download")
    public ResponseEntity<byte[]> downloadKnowledgeBase(@PathVariable Long id) {
        return null; // TODO
    }

    // ========== 搜索 ==========

    /** 按关键词搜索知识库。 */
    @GetMapping("/api/knowledgebase/search")
    public Result<List<KnowledgeBaseListItemDTO>> search(@RequestParam("keyword") String keyword) {
        return Result.success(List.of()); // TODO
    }

    // ========== 统计 ==========

    /** 获取知识库统计信息。 */
    @GetMapping("/api/knowledgebase/stats")
    public Result<KnowledgeBaseStatsDTO> getStatistics() {
        return Result.success(null); // TODO
    }

    // ========== 向量化管理 ==========

    /**
     * 重新向量化知识库（手动重试）。
     * 用于向量化失败后的手动触发。受限流保护。
     */
    @PostMapping("/api/knowledgebase/{id}/revectorize")
    public Result<Void> revectorize(@PathVariable Long id) {
        return Result.success(null); // TODO
    }
}
