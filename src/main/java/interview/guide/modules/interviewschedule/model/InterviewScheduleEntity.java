package interview.guide.modules.interviewschedule.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 面试日程实体。
 * <p>
 * 对应数据库 {@code interview_schedules} 表。
 * 记录一场面试安排的全部信息，包括公司、职位、时间、方式、状态等。
 */
@Entity
@Table(name = "interview_schedules", indexes = {
    @Index(name = "idx_schedule_status", columnList = "status"),
    @Index(name = "idx_schedule_time", columnList = "interviewTime")
})
@Getter
@Setter
@NoArgsConstructor
public class InterviewScheduleEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 公司名称。 */
    @Column(nullable = false)
    private String companyName;

    /** 职位名称。 */
    @Column(nullable = false)
    private String position;

    /** 面试时间（ISO 8601）。 */
    @Column(nullable = false)
    private LocalDateTime interviewTime;

    /** 面试方式：现场/视频/电话。 */
    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private InterviewType interviewType;

    /** 会议链接（视频面试时）。 */
    @Column(length = 500)
    private String meetingLink;

    /** 面试轮次（第几轮面试）。 */
    private Integer roundNumber;

    /** 面试官姓名或角色。 */
    private String interviewer;

    /** 备注（如携带材料、面试地址等）。 */
    @Column(columnDefinition = "TEXT")
    private String notes;

    /** 面试状态。默认 PENDING。 */
    @Enumerated(EnumType.STRING)
    @Column(length = 20, nullable = false)
    private InterviewStatus status = InterviewStatus.PENDING;

    /** 创建时间。由 @PrePersist 自动设置。 */
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /** 更新时间。每次修改时自动更新。 */
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
