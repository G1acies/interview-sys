package interview.guide.modules.llmprovider.repository;

import interview.guide.modules.llmprovider.model.LlmProviderEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface LlmProviderRepository extends JpaRepository<LlmProviderEntity, Long> {
    Optional<LlmProviderEntity> findByProviderId(String providerId);
    boolean existsByProviderId(String providerId);
    void deleteByProviderId(String providerId);
}
