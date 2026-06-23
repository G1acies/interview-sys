package interview.guide.modules.interview.model;

/**
 * 当前题目响应。
 * <p>
 * 用于 {@code GET /api/interview/sessions/{sessionId}/question} 的返回。
 * 前端根据 {@code completed} 判断是否需要继续答题。
 *
 * @param completed 是否已答完所有题
 * @param question  当前题目（已答完时为 null）
 * @param message   提示信息（已答完时告知用户）
 */
public record CurrentQuestionResponse(
    boolean completed,
    InterviewQuestionDTO question,
    String message
) {}
