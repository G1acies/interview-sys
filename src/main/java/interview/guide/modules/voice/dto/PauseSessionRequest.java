package interview.guide.modules.voice.dto;

/**
 * 暂停会话请求体。
 *
 * @param reason 暂停原因，如 "user_initiated"、"timeout"、"network_issue"
 */
public record PauseSessionRequest(
    String reason
) {}
