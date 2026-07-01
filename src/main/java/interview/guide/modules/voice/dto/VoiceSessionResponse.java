package interview.guide.modules.voice.dto;

/**
 * 语音面试会话响应 DTO。
 * <p>
 * 与前端 {@code SessionResponse} 一一对应。
 * webSocketUrl 由服务端根据当前请求上下文动态构造。
 *
 * @param sessionId       会话 ID
 * @param roleType        面试官角色
 * @param currentPhase    当前面试阶段
 * @param status          会话状态
 * @param startTime       开始时间（ISO-8601）
 * @param plannedDuration 计划时长（分钟）
 * @param webSocketUrl    WebSocket 连接地址
 */
public record VoiceSessionResponse(
    Long sessionId,
    String roleType,
    String currentPhase,
    String status,
    String startTime,
    Integer plannedDuration,
    String webSocketUrl
) {}
