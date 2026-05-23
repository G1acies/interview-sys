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

@RestController
@RequiredArgsConstructor
public class ResumeController {

    private final ResumeService resumeService;
    private final FileStorageService fileStorageService;
    private final FileValidationService fileValidationService;

    @GetMapping("/api/resume/list")
    public Result<List<ResumeListItemDTO>> list() {
        return Result.success(resumeService.listAll());
    }

    @GetMapping("/api/resume/{id}")
    public Result<ResumeEntity> getById(@PathVariable Long id) {
        return Result.success(resumeService.getById(id));
    }

    @PostMapping("/api/resume/create")
    public Result<ResumeEntity> create(
            @RequestParam String fileName,
            @RequestParam String fileUrl) {
        return Result.success(resumeService.create(fileName, fileUrl));
    }

    @DeleteMapping("/api/resume/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        resumeService.delete(id);
        return Result.success();
    }


    @PostMapping("/api/resume/upload")
    public Result<ResumeEntity> upload(@RequestParam("file") MultipartFile file) {
        fileValidationService.validateFile(file, 20 * 1024 * 1024, "简历");
        String fileKey = fileStorageService.uploadResume(file);
        String fileUrl = fileStorageService.getFileUrl(fileKey);
        return Result.success(resumeService.create(file.getOriginalFilename(), fileUrl));
    }
}
