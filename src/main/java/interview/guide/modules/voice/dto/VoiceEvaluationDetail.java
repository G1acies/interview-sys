package interview.guide.modules.voice.dto;

import java.util.List;

/**
 * 语音面试评估详情。
 * <p>
 * 与前端 {@code VoiceEvaluationDetail} 一一对应。
 * 作为 JSON 整体存储在 VoiceInterviewSessionEntity.evaluationJson 中。
 *
 * @param sessionId       会话 ID
 * @param totalQuestions  总问题数
 * @param overallScore    总分（0-100）
 * @param overallFeedback 总体评语
 * @param strengths       优势列表
 * @param improvements    改进建议列表
 * @param answers         各题评估详情
 */
public record VoiceEvaluationDetail(
    Long sessionId,
    Integer totalQuestions,
    Integer overallScore,
    String overallFeedback,
    List<String> strengths,
    List<String> improvements,
    List<VoiceAnswerDetail> answers
) {}
