package interview.guide.common.evaluation;

import java.util.List;

/**
 * 通用面试评估报告（文字面试和语音面试共用）。
 * <p>
 * 由 {@link UnifiedEvaluationService} 生成，包含总分、分类得分、
 * 每题评估详情、综合评语和参考答案。
 *
 * @param sessionId        会话唯一标识
 * @param totalQuestions   题目总数
 * @param overallScore     总分（0-100）
 * @param categoryScores   各分类得分
 * @param questionDetails  每题评估详情
 * @param overallFeedback  总体评价
 * @param strengths        优势列表
 * @param improvements     改进建议
 * @param referenceAnswers 参考答案
 */
public record EvaluationReport(
    String sessionId,
    int totalQuestions,
    int overallScore,
    List<CategoryScore> categoryScores,
    List<QuestionEvaluation> questionDetails,
    String overallFeedback,
    List<String> strengths,
    List<String> improvements,
    List<ReferenceAnswer> referenceAnswers
) {
    /**
     * 分类得分。
     *
     * @param category      分类名称，如 "JAVA"、"MYSQL"
     * @param score         该类平均分（0-100）
     * @param questionCount 该类题目数量
     */
    public record CategoryScore(
        String category,
        int score,
        int questionCount
    ) {}

    /**
     * 问题评估详情。
     *
     * @param questionIndex 问题索引
     * @param question      题目原文
     * @param category      题目分类
     * @param userAnswer    用户作答
     * @param score         本题得分（0-100）
     * @param feedback      作答反馈
     */
    public record QuestionEvaluation(
        int questionIndex,
        String question,
        String category,
        String userAnswer,
        int score,
        String feedback
    ) {}

    /**
     * 参考答案。
     *
     * @param questionIndex   问题索引
     * @param question        题目原文
     * @param referenceAnswer 参考答案
     * @param keyPoints       关键点列表
     */
    public record ReferenceAnswer(
        int questionIndex,
        String question,
        String referenceAnswer,
        List<String> keyPoints
    ) {}
}
