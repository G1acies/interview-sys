package interview.guide.modules.interview.repository;

import interview.guide.modules.interview.model.InterviewAnswerEntity;
import interview.guide.modules.interview.model.InterviewSessionEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 面试答案 Repository。
 * <p>
 * 提供答案记录的 CRUD 及按会话、问题索引的查询。
 * <p>
 * 核心查询方法：
 * <ul>
 *   <li>{@link #findBySessionOrderByQuestionIndex(InterviewSessionEntity)} — 按会话实体查所有答案</li>
 *   <li>{@link #findBySession_SessionIdOrderByQuestionIndex(String)} — 按会话的 sessionId 查答案</li>
 *   <li>{@link #findBySession_SessionIdAndQuestionIndex(String, Integer)} — 用于 upsert 语义的精确查找</li>
 * </ul>
 */
@Repository
public interface InterviewAnswerRepository extends JpaRepository<InterviewAnswerEntity, Long> {

    /**
     * 根据会话实体查找所有答案（按问题索引排序）。
     */
    List<InterviewAnswerEntity> findBySessionOrderByQuestionIndex(InterviewSessionEntity session);

    /**
     * 根据会话的内部 ID 查找所有答案（按问题索引排序）。
     */
    List<InterviewAnswerEntity> findBySessionIdOrderByQuestionIndex(Long sessionId);

    /**
     * 根据会话的对外 sessionId 查找所有答案（按问题索引排序）。
     * <p>
     * 最常用的查询方式，因为前端传递的是 sessionId（UUID），不是内部 ID。
     * Spring Data JPA 的 {@code _} 语法表示跨关联属性查询。
     */
    List<InterviewAnswerEntity> findBySession_SessionIdOrderByQuestionIndex(String sessionId);

    /**
     * 根据 sessionId + questionIndex 精确查找单条答案。
     * <p>
     * 用于暂存/提交答案时的 upsert：先查是否存在，存在则更新，不存在则新建。
     */
    Optional<InterviewAnswerEntity> findBySession_SessionIdAndQuestionIndex(String sessionId, Integer questionIndex);
}
