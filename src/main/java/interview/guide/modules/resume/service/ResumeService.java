package interview.guide.modules.resume.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.resume.model.ResumeEntity;
import interview.guide.modules.resume.model.ResumeListItemDTO;
import interview.guide.modules.resume.repository.ResumeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class ResumeService {

    private final ResumeRepository resumeRepository;

    @Transactional(readOnly = true)
    public List<ResumeListItemDTO> listAll() {
        return resumeRepository.findAll()
                .stream()
                .map(e -> new ResumeListItemDTO(e.getId(), e.getFileName(), e.getStatus(), e.getCreatedAt()))
                .toList();
    }

    @Transactional(readOnly = true)
    public ResumeEntity getById(Long id) {
        return resumeRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESUME_NOT_FOUND, "简历 ID " + id + " 不存在"));
    }

    @Transactional
    public ResumeEntity create(String fileName, String fileUrl) {
        ResumeEntity entity = ResumeEntity.builder()
                .fileName(fileName)
                .fileUrl(fileUrl)
                .status("PENDING")
                .createdAt(java.time.LocalDateTime.now())
                .updatedAt(java.time.LocalDateTime.now())
                .build();
        ResumeEntity saved = resumeRepository.save(entity);
        log.info("简历记录已创建: id={}, fileName={}", saved.getId(), fileName);
        return saved;
    }

    /**
     * 保存解析后的文本内容，将状态更新为 COMPLETED。
     * 如果解析失败，调用方应传入 null 或空文本，状态设为 FAILED。
     */
    @Transactional
    public void saveParsedContent(Long id, String rawText) {
        ResumeEntity entity = getById(id);
        entity.setRawText(rawText);
        entity.setStatus(rawText != null && !rawText.isBlank() ? "PARSED" : "FAILED");
        entity.setUpdatedAt(java.time.LocalDateTime.now());
        resumeRepository.save(entity);
        log.info("简历解析完成: id={}, 文本长度={}, 状态={}", id,
                rawText != null ? rawText.length() : 0, entity.getStatus());
    }

    /**
     * 保存 AI 分析结果（JSON），状态更新为 COMPLETED。
     */
    @Transactional
    public void saveAnalysisResult(Long id, String analysisJson) {
        ResumeEntity entity = getById(id);
        entity.setAnalysisResult(analysisJson);
        entity.setStatus(analysisJson != null ? "COMPLETED" : "FAILED");
        entity.setUpdatedAt(java.time.LocalDateTime.now());
        resumeRepository.save(entity);
        log.info("AI 简历分析完成: id={}, 状态={}", id, entity.getStatus());
    }

    @Transactional
    public void delete(Long id) {
        ResumeEntity entity = getById(id);
        resumeRepository.delete(entity);
        log.info("Resume deleted: id={}", id);
    }
}
