package interview.guide.modules.interviewschedule.model;

/**
 * 解析面试邀约文本的响应 DTO。
 * <p>
 * 规则解析优先，规则无法解析时降级为 AI 解析。
 *
 * @param success     是否解析成功
 * @param data        解析出的结构化数据（失败时为 null）
 * @param confidence  置信度 0~1
 * @param parseMethod 解析方式：rule（规则）或 ai（AI）
 * @param log         解析过程日志
 */
public record ParseResponse(
    boolean success,
    CreateInterviewRequest data,
    double confidence,
    String parseMethod,
    String log
) {}
