package interview.guide.modules.voice.repository;

import interview.guide.modules.voice.model.VoiceInterviewMessageEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 语音面试消息 Repository。
 * <p>
 * 提供消息的 CRUD 及按会话、阶段等维度的查询。
 */
@Repository
public interface VoiceInterviewMessageRepository extends JpaRepository<VoiceInterviewMessageEntity, Long> {

    /**
     * 按会话 ID 查询所有消息（按序号升序）。
     */
    List<VoiceInterviewMessageEntity> findBySessionIdOrderBySequenceNumAsc(Long sessionId);

    /**
     * 按会话 ID 和阶段查询消息。
     */
    List<VoiceInterviewMessageEntity> findBySessionIdAndPhaseOrderBySequenceNumAsc(Long sessionId, String phase);

    /**
     * 统计某会话的消息总数。
     */
    int countBySessionId(Long sessionId);
}
