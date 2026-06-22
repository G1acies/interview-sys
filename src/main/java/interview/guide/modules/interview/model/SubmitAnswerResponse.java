package interview.guide.modules.interview.model;

/**
 * 提交答案响应体。
 * <p>
 * 用户提交答案并进入下一题后，后端返回此响应。
 * 如果 {@code hasNextQuestion} 为 false，表示所有题目已答完，
 * 前端应引导用户查看报告或提交评估。
 *
 * @param hasNextQuestion 是否还有下一题
 * @param nextQuestion    下一题信息（无下一题时为 null）
 * @param currentIndex    当前已答题目索引
 * @param totalQuestions  题目总数
 */
public record SubmitAnswerResponse(
    boolean hasNextQuestion,
    InterviewQuestionDTO nextQuestion,
    int currentIndex,
    int totalQuestions
) {}
