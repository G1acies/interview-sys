package interview.guide.modules.voice.dto;

/**
 * 语音面试评估状态响应。
 * <p>
 * 与前端 {@code EvaluationStatusResponse} 一一对应。
 * 前端轮询此接口直到 evaluateStatus 变为 COMPLETED 或 FAILED。
 *
 * @param evaluateStatus 评估状态：PENDING / PROCESSING / COMPLETED / FAILED（可能为 null）
 * @param evaluateError  评估失败时的错误信息（可能为 null）
 * @param evaluation     评估结果详情（COMPLETED 时有值，可能为 null）
 */
public record VoiceEvaluationStatusResponse(
    String evaluateStatus,
    String evaluateError,
    VoiceEvaluationDetail evaluation
) {}
