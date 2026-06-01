package interview.guide.modules.resume.controller;

import interview.guide.common.result.Result;
import interview.guide.infrastructure.file.FileStorageService;
import interview.guide.infrastructure.file.FileValidationService;
import interview.guide.modules.resume.model.ResumeEntity;
import interview.guide.modules.resume.model.ResumeListItemDTO;
import interview.guide.modules.resume.service.ResumeService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * 简历管理 REST 接口。
 * <p>
 * 提供简历的增删查以及文件上传能力。
 */
@RestController
@RequiredArgsConstructor
public class ResumeController {

    private final ResumeService resumeService;
    private final FileStorageService fileStorageService;
    private final FileValidationService fileValidationService;

    /** 获取简历列表 */
    @GetMapping("/api/resume/list")
    public Result<List<ResumeListItemDTO>> list() {
        return Result.success(resumeService.listAll());
    }

    /** 获取单条简历详情 */
    @GetMapping("/api/resume/{id}")
    public Result<ResumeEntity> getById(@PathVariable Long id) {
        return Result.success(resumeService.getById(id));
    }

    /**
     * 手动创建简历记录（仅写数据库，不涉及文件上传）。
     * <p>
     * 适用场景：已有存储好的文件 URL，直接登记一条记录。
     */
    @PostMapping("/api/resume/create")
    public Result<ResumeEntity> create(
            @RequestParam String fileName,
            @RequestParam String fileUrl) {
        return Result.success(resumeService.create(fileName, fileUrl));
    }

    /** 删除简历记录 */
    @DeleteMapping("/api/resume/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        resumeService.delete(id);
        return Result.success();
    }

    /**
     * 完整的上传流程：校验 → 存储 → 入库。
     *
     * <h3>请求格式</h3>
     * <pre>{@code
     * POST /api/resume/upload
     * Content-Type: multipart/form-data
     * Body: file=<二进制文件>
     * }</pre>
     *
     * <h3>内部流程</h3>
     * <ol>
     *   <li><b>校验</b> — FileValidationService 检查文件非空、不超过 20MB</li>
     *   <li><b>存储</b> — FileStorageService 将文件上传到 MinIO，返回 fileKey</li>
     *   <li><b>生成 URL</b> — 根据 fileKey 拼接可访问的 HTTP 地址</li>
     *   <li><b>入库</b> — ResumeService 将 fileName + fileUrl 写入数据库</li>
     *   <li><b>返回</b> — 包装为 Result 返回给前端</li>
     * </ol>
     *
     * @param file 前端通过 FormData 提交的文件，Spring 自动解析为 MultipartFile
     * @return 新创建的简历实体（含 id、fileName、fileUrl、status）
     */
    @PostMapping("/api/resume/upload")
    public Result<ResumeEntity> upload(@RequestParam("file") MultipartFile file) {
        // 第1步：安全校验 — 空文件、超大文件直接拒绝
        fileValidationService.validateFile(file, 20 * 1024 * 1024, "简历");

        // 第2步：上传到 MinIO — 返回存储键如 resumes/2026/06/01/xxx.pdf
        String fileKey = fileStorageService.uploadResume(file);

        // 第3步：根据存储键生成可访问的文件 URL
        String fileUrl = fileStorageService.getFileUrl(fileKey);

        // 第4步：把文件信息写入数据库，状态为 PENDING（待解析）
        return Result.success(resumeService.create(file.getOriginalFilename(), fileUrl));
    }
}
