package interview.guide.modules.voice.model;

import interview.guide.common.model.AsyncTaskStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 语音面试会话实体。
 * <p>
 * 对应数据库表 {@code voice_interview_sessions}，存储每次语音面试的完整信息。
 * 包括面试方向、阶段配置、状态流转、时长统计等。
 * <p>
 * 状态流转：
 * <pre>
 * CREATED ──→ IN_PROGRESS ──→ PAUSED ──→ IN_PROGRESS ──→ COMPLETED ──→ EVALUATED
 *                │                                                │
 *                └──────────────────────────────────────────────────┘
 *                         直接结束（未暂停）
 * </pre>
 * PAUSED 可通过 resume 回到 IN_PROGRESS；COMPLETED 为终态。
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "voice_interview_sessions", indexes = {
    @Index(name = "idx_voice_session_status_created", columnList = "status,created_at"),
    @Index(name = "idx_voice_session_skill_created", columnList = "skill_id,created_at"),
})
public class VoiceInterviewSessionEntity {

    /** 主键（自增），对前端暴露为 sessionId */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 面试官角色 */
    @Column(length = 50)
    private String roleType = "interviewer";

    /** 面试方向 ID，如 "java-backend", "frontend", "custom" */
    @Column(name = "skill_id", length = 64)
    private String skillId = "java-backend";

    /** 难度级别：junior / mid / senior */
    @Column(length = 16)
    private String difficulty = "mid";

    /** 自定义 JD 文本（可选） */
    @Column(columnDefinition = "TEXT")
    private String customJdText;

    /** 关联的简历 ID（可选） */
    @Column(name = "resume_id")
    private Long resumeId;

    /** 是否开启自我介绍环节 */
    private Boolean introEnabled = false;

    /** 是否开启技术问答环节 */
    private Boolean techEnabled = true;

    /** 是否开启项目问答环节 */
    private Boolean projectEnabled = true;

    /** 是否开启 HR 问答环节 */
    private Boolean hrEnabled = false;

    /** 计划面试时长（分钟） */
    @Column(name = "planned_duration")
    private Integer plannedDuration = 15;

    /** LLM 提供商标识，如 "dashscope"、"openai" */
    @Column(name = "llm_provider", length = 50)
    private String llmProvider = "dashscope";

    // ── 状态 ──

    /** 会话状态 */
    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private VoiceSessionStatus status = VoiceSessionStatus.CREATED;

    /** 当前面试阶段：INTRO / TECH / PROJECT / HR */
    @Column(name = "current_phase", length = 20)
    private String currentPhase = "INTRO";

    // ── 时间 ──

    /** 面试开始时间 */
    @Column(name = "start_time")
    private LocalDateTime startTime;

    /** 面试结束时间 */
    @Column(name = "end_time")
    private LocalDateTime endTime;

    /** 暂停时间（用于计算暂停时长） */
    @Column(name = "paused_at")
    private LocalDateTime pausedAt;

    /** 暂停原因 */
    @Column(name = "pause_reason", length = 100)
    private String pauseReason;

    /** 实际面试时长（秒，不含暂停时间） */
    @Column(name = "actual_duration")
    private Integer actualDuration;

    /** 消息总数 */
    @Column(name = "message_count")
    private Integer messageCount = 0;

    // ── 评估 ──

    /** 异步评估状态 */
    @Enumerated(EnumType.STRING)
    @Column(name = "evaluate_status", length = 20)
    private AsyncTaskStatus evaluateStatus;

    /** 评估错误信息 */
    @Column(name = "evaluate_error", length = 500)
    private String evaluateError;

    /** 评估结果 JSON（完整的 VoiceEvaluationDetail） */
    @Column(name = "evaluation_json", columnDefinition = "TEXT")
    private String evaluationJson;

    // ── 审计字段 ──

    /** 创建时间 */
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    /** 更新时间 */
    @Column(name = "updated_at")
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

    /**
     * 语音面试会话状态枚举。
     */
    public enum VoiceSessionStatus {
        CREATED,
        IN_PROGRESS,
        PAUSED,
        COMPLETED,
        EVALUATED
    }
}
