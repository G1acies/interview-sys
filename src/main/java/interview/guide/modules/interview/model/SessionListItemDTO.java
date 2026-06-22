package interview.guide.modules.interview.model;

import interview.guide.common.model.AsyncTaskStatus;
import interview.guide.modules.interview.model.InterviewSessionEntity.SessionStatus;

import java.time.LocalDateTime;

/**
 * 面试会话列表项 DTO（轻量级）。
 * <p>
 * 用于面试历史列表页展示，只包含概要信息，不含题目/答案等大字段。
 * 通过 {@link #from(InterviewSessionEntity)} 工厂方法从实体转换。
 *
 * @param sessionId      会话唯一标识
 * @param skillId        面试方向 ID
 * @param difficulty     难度级别
 * @param resumeId       关联简历 ID（通用面试为 null）
 * @param totalQuestions 题目总数
 * @param status         会话状态
 * @param evaluateStatus 异步评估状态
 * @param evaluateError  评估错误信息
 * @param overallScore   总分（未评估时为 null）
 * @param createdAt      创建时间
 * @param completedAt    完成时间
 */
public record SessionListItemDTO(
    String sessionId,
    String skillId,
    String difficulty,
    Long resumeId,
    int totalQuestions,
    SessionStatus status,
    AsyncTaskStatus evaluateStatus,
    String evaluateError,
    Integer overallScore,
    LocalDateTime createdAt,
    LocalDateTime completedAt
) {
    /**
     * 从实体创建列表项 DTO。
     * <p>
     * 只取实体的基本字段，不加载关联集合（answers 等），避免 N+1 查询。
     *
     * @param e 面试会话实体
     * @return 列表项 DTO
     */
    public static SessionListItemDTO from(InterviewSessionEntity e) {
        return new SessionListItemDTO(
            e.getSessionId(),
            e.getSkillId(),
            e.getDifficulty(),
            e.getResumeId(),
            e.getTotalQuestions() != null ? e.getTotalQuestions() : 0,
            e.getStatus(),
            e.getEvaluateStatus(),
            e.getEvaluateError(),
            e.getOverallScore(),
            e.getCreatedAt(),
            e.getCompletedAt()
        );
    }
}
