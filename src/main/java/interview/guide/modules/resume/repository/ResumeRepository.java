package interview.guide.modules.resume.repository;

import interview.guide.modules.resume.model.ResumeEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ResumeRepository extends JpaRepository<ResumeEntity, Long> {

    Optional<ResumeEntity> findByFileHash(String fileHash);
}
