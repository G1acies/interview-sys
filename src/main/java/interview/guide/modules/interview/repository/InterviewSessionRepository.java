package interview.guide.modules.interview.repository;

import interview.guide.modules.interview.model.InterviewSessionEntity;
import interview.guide.modules.interview.model.InterviewSessionEntity.SessionStatus;
import interview.guide.modules.resume.model.ResumeEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 面试会话 Repository。
 * <p>
 * 提供面试会话的 CRUD 及按简历、状态等维度的查询。
 * 核心查询方法：
 * <ul>
 *   <li>{@link #findBySessionId(String)} — 按对外暴露的 sessionId 查找</li>
 *   <li>{@link #findByResumeIdOrderByCreatedAtDesc(Long)} — 查看某份简历的所有面试记录</li>
 *   <li>{@link #findFirstByResumeIdAndStatusInOrderByCreatedAtDesc(Long, List)} — 查找未完成会话</li>
 *   <li>{@link #findTop10ByResumeIdAndSkillIdOrderByCreatedAtDesc(Long, String)} — 历史题目去重</li>
 * </ul>
 */
@Repository
public interface InterviewSessionRepository extends JpaRepository<InterviewSessionEntity, Long> {

    /**
     * 根据会话 UUID 查找会话。
     */
    Optional<InterviewSessionEntity> findBySessionId(String sessionId);

    /**
     * 根据会话 UUID 查找，同时 JOIN FETCH 简历信息。
     * <p>
     * 用 LEFT JOIN FETCH 避免 N+1 问题：当需要读取简历文本进行评估时，
     * 一次查询加载所有数据，而非遍历时逐个触发 LAZY 查询。
     */
    @Query("SELECT s FROM InterviewSessionEntity s LEFT JOIN FETCH s.resume WHERE s.sessionId = :sessionId")
    Optional<InterviewSessionEntity> findBySessionIdWithResume(@Param("sessionId") String sessionId);

    /**
     * 根据简历实体查找所有面试会话（按创建时间倒序）。
     */
    List<InterviewSessionEntity> findByResumeOrderByCreatedAtDesc(ResumeEntity resume);

    /**
     * 根据简历 ID 查找所有面试会话（按创建时间倒序）。
     */
    List<InterviewSessionEntity> findByResumeIdOrderByCreatedAtDesc(Long resumeId);

    /**
     * 根据简历 ID 查找最近的 10 条面试记录（用于历史题目去重）。
     */
    List<InterviewSessionEntity> findTop10ByResumeIdOrderByCreatedAtDesc(Long resumeId);

    /**
     * 查找某份简历未完成的面试会话（CREATED 或 IN_PROGRESS 状态）。
     * <p>
     * 用户可能重复进入同一份简历的面试页面，此时应返回已有会话而非重新创建。
     * 返回最近创建的那个（按创建时间倒序取第一条）。
     */
    Optional<InterviewSessionEntity> findFirstByResumeIdAndStatusInOrderByCreatedAtDesc(
        Long resumeId,
        List<SessionStatus> statuses
    );

    /**
     * 根据简历 ID 和状态列表查找会话。
     */
    Optional<InterviewSessionEntity> findByResumeIdAndStatusIn(
        Long resumeId,
        List<SessionStatus> statuses
    );

    /**
     * 查找所有面试会话（按创建时间倒序）。
     */
    List<InterviewSessionEntity> findAllByOrderByCreatedAtDesc();

    /**
     * 根据 skillId 查找最近的 10 条面试记录（无简历的通用面试模式历史去重用）。
     */
    List<InterviewSessionEntity> findTop10BySkillIdOrderByCreatedAtDesc(String skillId);

    /**
     * 根据 resumeId + skillId 精确匹配，查找最近的 10 条面试记录。
     * <p>
     * 有简历时用此方法精确匹配，避免跨 Skill 的题目干扰去重。
     */
    List<InterviewSessionEntity> findTop10ByResumeIdAndSkillIdOrderByCreatedAtDesc(Long resumeId, String skillId);
}
