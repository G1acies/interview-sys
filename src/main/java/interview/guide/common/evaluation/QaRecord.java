package interview.guide.common.evaluation;

/**
 * 通用面试问答记录（文字面试和语音面试共用）。
 * <p>
 * 用于 {@link UnifiedEvaluationService#evaluate} 的输入，
 * 将原始面试题目和用户答案统一为通用格式，供 AI 评估。
 *
 * @param questionIndex 问题索引
 * @param question      题目原文
 * @param category      题目分类
 * @param userAnswer    用户作答（null 表示未回答）
 */
public record QaRecord(
    int questionIndex,
    String question,
    String category,
    String userAnswer
) {}
