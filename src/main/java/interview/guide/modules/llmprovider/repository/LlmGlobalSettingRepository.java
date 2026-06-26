package interview.guide.modules.llmprovider.repository;

import interview.guide.modules.llmprovider.model.LlmGlobalSettingEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface LlmGlobalSettingRepository extends JpaRepository<LlmGlobalSettingEntity, Long> {
}
