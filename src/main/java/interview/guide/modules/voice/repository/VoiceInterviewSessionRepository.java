package interview.guide.modules.voice.repository;

import interview.guide.modules.voice.model.VoiceInterviewSessionEntity;
import interview.guide.modules.voice.model.VoiceInterviewSessionEntity.VoiceSessionStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 语音面试会话 Repository。
 * <p>
 * 提供语音面试会话的 CRUD 及按状态、Skill 等维度的查询。
 */
@Repository
public interface VoiceInterviewSessionRepository extends JpaRepository<VoiceInterviewSessionEntity, Long> {

    /**
     * 按状态查询所有会话（按创建时间倒序）。
     */
    List<VoiceInterviewSessionEntity> findByStatusOrderByCreatedAtDesc(VoiceSessionStatus status);

    /**
     * 查询创建时间在指定时间之前的未完成会话（用于超时检测）。
     */
    List<VoiceInterviewSessionEntity> findByStatusInAndCreatedAtBefore(
        List<VoiceSessionStatus> statuses,
        java.time.LocalDateTime before
    );

    /**
     * 查询所有会话（按创建时间倒序）。
     */
    List<VoiceInterviewSessionEntity> findAllByOrderByCreatedAtDesc();
}
