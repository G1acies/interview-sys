package interview.guide.modules.resume.controller;

import interview.guide.common.result.Result;
import interview.guide.infrastructure.file.DocumentParseService;
import interview.guide.infrastructure.file.FileStorageService;
import interview.guide.infrastructure.file.FileValidationService;
import interview.guide.modules.resume.model.ResumeEntity;
import interview.guide.modules.resume.model.ResumeListItemDTO;
import interview.guide.modules.resume.service.ResumeAnalysisService;
import interview.guide.modules.resume.service.ResumeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * 简历管理 REST 接口。
 * <p>
 * 提供简历的增删查以及文件上传 + 解析能力。
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

    /** 健康检查 */
    @GetMapping("/health")
    public Result<?> health() {
        return Result.success("ok");
    }

    /** 获取简历列表 */
    @GetMapping("/list")
    public Result<List<ResumeListItemDTO>> list() {
        return Result.success(resumeService.listAll());
    }

    /** 获取单条简历详情 */
    @GetMapping("/{id}")
    public Result<ResumeEntity> getById(@PathVariable Long id) {
        return Result.success(resumeService.getById(id));
    }

    /** 手动创建简历记录 */
    @PostMapping("/create")
    public Result<ResumeEntity> create(
            @RequestParam String fileName,
            @RequestParam String fileUrl) {
        return Result.success(resumeService.create(fileName, fileUrl));
    }

    /** 删除简历记录 */
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        resumeService.delete(id);
        return Result.success();
    }

    /**
     * 上传 + Tika 解析 + AI 分析简历文件。
     *
     * <h3>完整流程</h3>
     * <ol>
     *   <li><b>校验</b> — 检查文件非空、不超过 20MB</li>
     *   <li><b>存储</b> — 上传到 MinIO，返回 fileKey</li>
     *   <li><b>入库</b> — 创建简历记录（状态 PENDING）</li>
     *   <li><b>Tika 解析</b> — 提取纯文本后保存（状态 PARSED）</li>
     *   <li><b>AI 分析</b> — 调用大模型提取结构化信息（状态 COMPLETED）</li>
     *   <li><b>返回</b> — 完整的简历实体</li>
     * </ol>
     */
    @PostMapping("/upload")
    public Result<ResumeEntity> upload(@RequestParam("file") MultipartFile file) {
        // 1. 校验
        fileValidationService.validateFile(file, 20 * 1024 * 1024, "简历");

        // 2. 上传到 MinIO
        String fileKey = fileStorageService.uploadResume(file);
        String fileUrl = fileStorageService.getFileUrl(fileKey);

        // 3. 先入库（状态 PENDING），拿到 id
        ResumeEntity entity = resumeService.create(file.getOriginalFilename(), fileUrl);

        // 4. Tika 解析文本内容
        try {
            String rawText = documentParseService.parseContent(file);
            resumeService.saveParsedContent(entity.getId(), rawText);
            entity = resumeService.getById(entity.getId());

            // 5. AI 分析 — 提取结构化信息
            try {
                String analysisJson = resumeAnalysisService.analyzeToJson(rawText);
                resumeService.saveAnalysisResult(entity.getId(), analysisJson);
                entity = resumeService.getById(entity.getId());
            } catch (Exception e) {
                log.warn("AI 简历分析失败，Tika 解析已保存: id={}, error={}", entity.getId(), e.getMessage());
            }
        } catch (Exception e) {
            // Tika 解析失败，标记 FAILED
            resumeService.saveParsedContent(entity.getId(), null);
            entity = resumeService.getById(entity.getId());
        }

        return Result.success(entity);
    }
}
