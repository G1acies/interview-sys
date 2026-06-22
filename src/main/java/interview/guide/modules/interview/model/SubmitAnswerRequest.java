package interview.guide.modules.interview.model;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 提交答案请求体。
 * <p>
 * 用于向指定会话的某道题目提交用户的作答内容。
 * 有两种语义：
 * <ul>
 *   <li><b>POST /answers</b> — 提交 + 进入下一题，返回下一题信息</li>
 *   <li><b>PUT /answers</b> — 暂存答案，不进入下一题，返回 void</li>
 * </ul>
 *
 * @param sessionId     面试会话 ID
 * @param questionIndex 问题索引（从 0 开始）
 * @param answer        用户作答内容
 */
public record SubmitAnswerRequest(
    @NotBlank(message = "会话ID不能为空")
    String sessionId,

    @NotNull(message = "问题索引不能为空")
    @Min(value = 0, message = "问题索引无效")
    Integer questionIndex,

    @NotBlank(message = "答案不能为空")
    String answer
) {}
