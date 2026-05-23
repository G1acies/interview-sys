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
        log.info("Resume created: id={}, fileName={}", saved.getId(), fileName);
        return saved;
    }

    @Transactional
    public void delete(Long id) {
        ResumeEntity entity = getById(id);
        resumeRepository.delete(entity);
        log.info("Resume deleted: id={}", id);
    }
}
