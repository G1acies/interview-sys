package interview.guide.modules.interviewschedule.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDateTime;

/**
 * 创建/更新面试日程的请求 DTO。
 *
 * @param companyName    公司名称
 * @param position       职位名称
 * @param interviewTime  面试时间（ISO 8601）
 * @param interviewType  面试方式（可选）
 * @param meetingLink    会议链接（可选）
 * @param roundNumber    面试轮次（可选）
 * @param interviewer    面试官（可选）
 * @param notes          备注（可选）
 */
public record CreateInterviewRequest(
    @NotBlank(message = "公司名称不能为空")
    String companyName,

    @NotBlank(message = "职位不能为空")
    String position,

    @NotNull(message = "面试时间不能为空")
    LocalDateTime interviewTime,

    InterviewType interviewType,
    String meetingLink,
    Integer roundNumber,
    String interviewer,
    String notes
) {}
