package interview.guide.modules.resume.controller;

import interview.guide.common.exception.ErrorCode;
import interview.guide.common.result.Result;
import interview.guide.infrastructure.export.PdfExportService;
import interview.guide.infrastructure.file.DocumentParseService;
import interview.guide.infrastructure.file.FileStorageService;
import interview.guide.infrastructure.file.FileValidationService;
import interview.guide.modules.resume.model.ResumeEntity;
import interview.guide.modules.resume.service.ResumeAnalysisService;
import interview.guide.modules.resume.service.ResumeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;

/**
 * 简历管理 REST 接口。
 */
@Slf4j
@RestController
@RequestMapping("/api/resumes")
@RequiredArgsConstructor
public class ResumeController {

    private final ResumeService resumeService;
    private final FileStorageService fileStorageService;
    private final FileValidationService fileValidationService;
    private final DocumentParseService documentParseService;
    private final ResumeAnalysisService resumeAnalysisService;
    private final PdfExportService pdfExportService;

    // 给前端的列表项
    public record ResumeListItem(
            Long id,
            String filename,
            Long fileSize,
            String uploadedAt,
            int accessCount,
            Integer latestScore,
            String lastAnalyzedAt,
            int interviewCount,
            String analyzeStatus,
            String analyzeError,
            String storageUrl
    ) {
    }

    // 上传响应
    public record UploadStorageInfo(String fileKey, String fileUrl, Long resumeId) {
    }

    public record UploadResponse(UploadStorageInfo storage) {
    }

    // 详情响应
    public record ResumeDetail(
            Long id, String filename, Long fileSize, String contentType,
            String storageUrl, String uploadedAt, int accessCount,
            String resumeText, String analyzeStatus, String analyzeError,
            List<AnalysisItem> analyses, List<InterviewItem> interviews
    ) {
    }

    public record AnalysisItem(
            Long id, int overallScore, int contentScore, int structureScore,
            int skillMatchScore, int expressionScore, int projectScore,
            String summary, String analyzedAt, List<String> strengths,
            List<Object> suggestions
    ) {
    }

    // 统计
    public record ResumeStats(int totalCount, int totalInterviewCount, int totalAccessCount) {
    }

    public record InterviewItem(
            Long id, String sessionId, int totalQuestions, String status,
            String evaluateStatus, String evaluateError, Integer overallScore,
            String overallFeedback, String createdAt, String completedAt
    ) {
    }

    @GetMapping("/health")
    public Result<?> health() {
        return Result.success("ok");
    }

    /**
     * 获取全部简历列表
     */
    @GetMapping
    public Result<List<ResumeListItem>> list() {
        List<ResumeListItem> items = resumeService.listAll().stream()
                .map(this::toListItem)
                .toList();
        return Result.success(items);
    }

    /**
     * 获取简历统计
     */
    @GetMapping("/statistics")
    public Result<ResumeStats> statistics() {
        ResumeStats stats = new ResumeStats(
                (int) resumeService.count(), 0, 0);
        return Result.success(stats);
    }

    /**
     * 获取单条简历详情
     */
    @GetMapping("/{id}")
    public Result<ResumeEntity> getById(@PathVariable Long id) {
        return Result.success(resumeService.getById(id));
    }

    /**
     * 删除简历记录
     */
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        resumeService.delete(id);
        return Result.success();
    }

    /**
     * 上传 + Tika 解析 + AI 分析。
     */
    @PostMapping("/upload")
    public Result<UploadResponse> upload(@RequestParam("file") MultipartFile file) {
        // 1. 校验
        fileValidationService.validateFile(file, 20 * 1024 * 1024, "简历");

        // 2. 上传到 MinIO
        String fileKey = fileStorageService.uploadResume(file);
        String fileUrl = fileStorageService.getFileUrl(fileKey);

        // 3. 入库（PENDING）
        ResumeEntity entity = resumeService.create(
                file.getOriginalFilename(), fileUrl, file.getSize(), file.getContentType());

        // 4. Tika 解析
        try {
            String rawText = documentParseService.parseContent(file);
            resumeService.saveParsedContent(entity.getId(), rawText);
            entity = resumeService.getById(entity.getId());

            // 5. AI 分析
            try {
                String analysisJson = resumeAnalysisService.analyzeToJson(rawText);
                resumeService.saveAnalysisResult(entity.getId(), analysisJson);
                entity = resumeService.getById(entity.getId());
            } catch (Exception e) {
                log.warn("AI 简历分析失败: id={}, error={}", entity.getId(), e.getMessage());
            }
        } catch (Exception e) {
            resumeService.saveParsedContent(entity.getId(), null);
            entity = resumeService.getById(entity.getId());
        }

        UploadStorageInfo storage = new UploadStorageInfo(fileKey, fileUrl, entity.getId());
        return Result.success(new UploadResponse(storage));
    }

    /**
     * Entity → 前端 ResumeListItem
     */
    private ResumeListItem toListItem(ResumeEntity e) {
        return new ResumeListItem(
                e.getId(),
                e.getFileName(),
                e.getFileSize(),
                e.getCreatedAt() != null ? e.getCreatedAt().toString() : null,
                0,                          // accessCount
                null,                       // latestScore
                e.getUpdatedAt() != null ? e.getUpdatedAt().toString() : null,
                0,                          // interviewCount
                e.getStatus(),
                null,                       // analyzeError
                e.getFileUrl()
        );
    }

    /**
     * 获取简历详情 — 前端 ResumeDetail 格式
     */
    @GetMapping("/{id}/detail")
    public Result<ResumeDetail> getDetailById(@PathVariable Long id) {
        ResumeEntity e = resumeService.getDetailById(id);

        // 从 analysisResult JSON 提取分析数据
        List<AnalysisItem> analyses = buildAnalyses(e);
        int latestScore = analyses.isEmpty() ? 0 : analyses.get(0).overallScore;

        // 组装详情
        ResumeDetail detail = new ResumeDetail(
                e.getId(),
                e.getFileName(),
                e.getFileSize(),
                e.getContentType(),
                e.getFileUrl(),
                e.getCreatedAt() != null ? e.getCreatedAt().toString() : null,
                0,                                    // accessCount
                e.getRawText(),
                e.getStatus(),
                null,                                 // analyzeError
                analyses,
                Collections.emptyList()               // interviews 暂为空
        );

        return Result.success(detail);
    }

    /**
     * 导出简历分析报告为 PDF。
     *
     * <h3>和普通 JSON 接口的区别</h3>
     * <ul>
     *   <li>返回二进制 {@code application/pdf}，不是 JSON</li>
     *   <li>用 {@link ResponseEntity} 而非 {@link Result}，因为 Content-Type 不同</li>
     *   <li>错误时返回 JSON + 500 状态码，不经过 GlobalExceptionHandler</li>
     * </ul>
     */
    @GetMapping("/{resumeId}/export")
    public ResponseEntity<?> export(@PathVariable Long resumeId) {
        ResumeEntity entity = resumeService.getById(resumeId);

        try {
            byte[] pdfBytes = pdfExportService.exportResume(entity);

            // RFC 5987: filename*=UTF-8''xxx 支持中文文件名
            String encoded = URLEncoder.encode(
                    (entity.getFileName() != null ? entity.getFileName() : "简历报告") + ".pdf",
                    StandardCharsets.UTF_8);

            return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_PDF)
                    .header("Content-Disposition",
                            "attachment; filename*=UTF-8''" + encoded)
                    .body(pdfBytes);
        } catch (Exception e) {
            log.error("导出 PDF 失败: resumeId={}, error={}", resumeId, e.getMessage(), e);
            // 不经过 GlobalExceptionHandler，手动构造错误 JSON
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body("{\"code\":5001,\"message\":\"PDF 导出失败: " + e.getMessage() + "\"}");
        }
    }

    private List<AnalysisItem> buildAnalyses(ResumeEntity e) {
        if (e.getAnalysisResult() == null || e.getAnalysisResult().isBlank()) {
            return Collections.emptyList();
        }
        try {
            // 从 JSON 提取技能数作为简易评分
            var om = new tools.jackson.databind.ObjectMapper();
            var node = om.readTree(e.getAnalysisResult());
            int skillCount = node.has("skills") ? node.get("skills").size() : 0;
            int expCount = node.has("experience") ? node.get("experience").size() : 0;
            int eduCount = node.has("education") ? node.get("education").size() : 0;

            int totalScore = Math.min(100, skillCount * 5 + expCount * 10 + eduCount * 10);

            AnalysisItem item = new AnalysisItem(
                    e.getId(),
                    totalScore,
                    Math.min(25, skillCount * 2),
                    Math.min(20, expCount * 3),
                    Math.min(25, skillCount * 2),
                    Math.min(15, 10),
                    Math.min(15, expCount * 3),
                    "AI 分析完成",
                    e.getUpdatedAt() != null ? e.getUpdatedAt().toString() : null,
                    List.of(),
                    List.of()
            );
            return List.of(item);
        } catch (Exception ex) {
            log.warn("解析 analysisResult JSON 失败: id={}, error={}", e.getId(), ex.getMessage());
            return Collections.emptyList();
        }
    }

    @PostMapping("/{id}/reanalyze")
    public Result<Void> reanalyzeResume(@PathVariable Long id) {
        ResumeEntity entity = resumeService.getById(id);
        if (entity.getRawText() == null || entity.getRawText().isBlank()) {
            return Result.error(ErrorCode.RESUME_ANALYSIS_FAILED.getCode(),
                    "简历文本为空，无法重新分析。请重新上传文件");
        }
        // AI 分析
        try {
            String analysisJson = resumeAnalysisService.analyzeToJson(entity.getRawText());
            resumeService.saveAnalysisResult(entity.getId(), analysisJson);
            return Result.success();
        } catch (Exception e) {
            log.warn("AI 简历分析失败: id={}, error={}", entity.getId(), e.getMessage());
            return Result.error(ErrorCode.RESUME_ANALYSIS_FAILED.getCode(),
                    "AI 分析失败: " + e.getMessage());
        }
    }


}
