package interview.guide.modules.voice.dto;

/**
 * 语音面试会话元数据（列表用）。
 * <p>
 * 与前端 {@code SessionMeta} 一一对应。
 *
 * @param sessionId      会话 ID
 * @param roleType       面试官角色
 * @param status         会话状态
 * @param currentPhase   当前面试阶段
 * @param createdAt      创建时间（ISO-8601）
 * @param updatedAt      更新时间（ISO-8601）
 * @param actualDuration 实际面试时长（秒，可能为 null）
 * @param messageCount   消息总数
 * @param evaluateStatus 评估状态（PENDING / PROCESSING / COMPLETED / FAILED，可能为 null）
 * @param evaluateError  评估错误信息（可能为 null）
 */
public record VoiceSessionMeta(
    Long sessionId,
    String roleType,
    String status,
    String currentPhase,
    String createdAt,
    String updatedAt,
    Integer actualDuration,
    Integer messageCount,
    String evaluateStatus,
    String evaluateError
) {}
