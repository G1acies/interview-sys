package interview.guide.modules.interview.model;

import interview.guide.common.model.AsyncTaskStatus;

import java.time.LocalDateTime;

/**
 * 面试历史列表项 DTO。
 * <p>
 * 用于简历详情页中展示"关联的面试记录"列表。
 * 与 {@link SessionListItemDTO} 不同，此 DTO 更轻量，
 * 专注于展示在简历上下文中，不包含 skillId/difficulty 等字段。
 *
 * @param id              数据库主键
 * @param sessionId       会话唯一标识
 * @param totalQuestions  题目总数
 * @param status          会话状态
 * @param evaluateStatus  异步评估状态
 * @param evaluateError   评估错误信息
 * @param overallScore    总分
 * @param createdAt       创建时间
 * @param completedAt     完成时间
 */
public record InterviewHistoryItemDTO(
    Long id,
    String sessionId,
    Integer totalQuestions,
    String status,
    String evaluateStatus,
    String evaluateError,
    Integer overallScore,
    LocalDateTime createdAt,
    LocalDateTime completedAt
) {}
