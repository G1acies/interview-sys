package interview.guide.modules.interviewschedule.model;

import jakarta.validation.constraints.NotBlank;

/**
 * 解析面试邀约文本的请求 DTO。
 *
 * @param rawText 面试邀约的原始文本（邮件内容、飞书链接等）
 * @param source  来源平台（feishu / tencent / zoom / other），辅助解析
 */
public record ParseRequest(
    @NotBlank(message = "解析文本不能为空")
    String rawText,
    String source
) {}
