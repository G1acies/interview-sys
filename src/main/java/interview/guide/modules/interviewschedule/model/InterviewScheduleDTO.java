package interview.guide.modules.interviewschedule.model;

import java.time.LocalDateTime;

/**
 * 面试日程列表项/详情 DTO。
 * <p>
 * 对应前端 {@code InterviewSchedule} 接口，由 {@link InterviewScheduleEntity} 转换而来。
 *
 * @param id             主键
 * @param companyName    公司名称
 * @param position       职位
 * @param interviewTime  面试时间
 * @param interviewType  面试方式
 * @param meetingLink    会议链接
 * @param roundNumber    面试轮次
 * @param interviewer    面试官
 * @param notes          备注
 * @param status         状态
 * @param createdAt      创建时间
 * @param updatedAt      更新时间
 */
public record InterviewScheduleDTO(
    Long id,
    String companyName,
    String position,
    LocalDateTime interviewTime,
    InterviewType interviewType,
    String meetingLink,
    Integer roundNumber,
    String interviewer,
    String notes,
    InterviewStatus status,
    LocalDateTime createdAt,
    LocalDateTime updatedAt
) {}
