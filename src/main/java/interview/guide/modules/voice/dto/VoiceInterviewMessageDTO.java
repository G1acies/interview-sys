package interview.guide.modules.voice.dto;

/**
 * 语音面试消息 DTO。
 * <p>
 * 与前端 {@code InterviewMessage} 一一对应。
 *
 * @param id                  消息 ID
 * @param sessionId           会话 ID
 * @param messageType         消息类型
 * @param phase               所属面试阶段
 * @param userRecognizedText  用户 ASR 识别文本
 * @param aiGeneratedText     用户回答后的 AI 评语/追问文本
 * @param timestamp           消息时间戳（ISO-8601）
 * @param sequenceNum         消息序号
 */
public record VoiceInterviewMessageDTO(
    Long id,
    Long sessionId,
    String messageType,
    String phase,
    String userRecognizedText,
    String aiGeneratedText,
    String timestamp,
    Integer sequenceNum
) {}
