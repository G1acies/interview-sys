package interview.guide.modules.interview.model;

import interview.guide.common.model.AsyncTaskStatus;
import interview.guide.modules.resume.model.ResumeEntity;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 面试会话实体。
 * <p>
 * 对应数据库表 {@code interview_sessions}，存储每次模拟面试的完整信息。
 * 包括面试的主题（skillId）、难度、题目列表（JSON 格式）、答案记录、
 * 评分结果以及评估状态等。
 * <p>
 * 设计说明：
 * <ul>
 *   <li><b>questionsJson 为什么存 JSON 而非关联表</b> — 题目是一次性生成的，不会单独增删改，
 *       存 JSON 减少一次 JOIN，读写更快。Redis 缓存中也用同样的 JSON 格式。</li>
 *   <li><b>简历可选</b> — resumeId 为 null 时表示通用面试，无简历的纯方向题。</li>
 *   <li><b>级联删除</b> — {@link CascadeType#ALL} + {@code orphanRemoval = true}，
 *       删除会话时自动删除关联的所有答案，无需手动清理。</li>
 * </ul>
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "interview_sessions", indexes = {
    @Index(name = "idx_interview_session_resume_created", columnList = "resume_id,created_at"),
    @Index(name = "idx_interview_session_resume_status_created", columnList = "resume_id,status,created_at"),
    @Index(name = "idx_interview_session_skill_created", columnList = "skillId,createdAt")
})
public class InterviewSessionEntity {

    /** 主键（自增） */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 会话唯一标识（UUID 字符串，对外暴露的 ID） */
    @Column(nullable = false, unique = true, length = 36)
    private String sessionId;

    /** 面试方向 ID，如 "java-backend", "frontend", "custom" */
    @Column(length = 64)
    private String skillId = "java-backend";

    /** 难度级别：junior（初级）/ mid（中级）/ senior（高级） */
    @Column(length = 16)
    private String difficulty = "mid";

    /** 简历 ID（直接映射 FK 列，避免 LAZY 加载触发额外查询） */
    @Column(name = "resume_id", insertable = false, updatable = false)
    private Long resumeId;

    /** 关联的简历实体（可选，无简历时为 null 表示通用面试） */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "resume_id")
    private ResumeEntity resume;

    /** 题目总数 */
    private Integer totalQuestions;

    /** 当前问题索引（从 0 开始） */
    private Integer currentQuestionIndex = 0;

    /** 会话状态：CREATED → IN_PROGRESS → COMPLETED / EVALUATED */
    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private SessionStatus status = SessionStatus.CREATED;

    /** 题目列表（JSON 格式，序列化自 List{@literal <}InterviewQuestionDTO{@literal >}） */
    @Column(columnDefinition = "TEXT")
    private String questionsJson;

    /** 总分（0-100） */
    private Integer overallScore;

    /** 总体评价 */
    @Column(columnDefinition = "TEXT")
    private String overallFeedback;

    /** 优势列表（JSON 格式） */
    @Column(columnDefinition = "TEXT")
    private String strengthsJson;

    /** 改进建议列表（JSON 格式） */
    @Column(columnDefinition = "TEXT")
    private String improvementsJson;

    /** 参考答案列表（JSON 格式） */
    @Column(columnDefinition = "TEXT")
    private String referenceAnswersJson;

    /** 面试答案列表（级联：保留会话时自动保留答案，删除时自动删除） */
    @OneToMany(mappedBy = "session", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<InterviewAnswerEntity> answers = new ArrayList<>();

    /** 创建时间 */
    @Column(nullable = false)
    private LocalDateTime createdAt;

    /** 完成时间（面试结束或评估完成时设置） */
    private LocalDateTime completedAt;

    /** 异步评估状态（可为 null，表示尚未触发评估） */
    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private AsyncTaskStatus evaluateStatus;

    /** 评估错误信息 */
    @Column(length = 500)
    private String evaluateError;

    /** LLM 提供商标识，如 "dashscope"、"openai" */
    @Column(length = 50)
    private String llmProvider = "dashscope";

    /**
     * 会话状态枚举。
     * <p>
     * 状态流转：
     * <pre>
     * CREATED ──→ IN_PROGRESS ──→ COMPLETED ──→ EVALUATED
     * </pre>
     * COMPLETED 表示用户已答完所有题（或提前交卷），
     * EVALUATED 表示 AI 已完成评估并生成了报告。
     */
    public enum SessionStatus {
        CREATED,
        IN_PROGRESS,
        COMPLETED,
        EVALUATED
    }

    /** 持久化前自动设置创建时间 */
    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }

    /** 快捷添加答案并建立双向关联 */
    public void addAnswer(InterviewAnswerEntity answer) {
        answers.add(answer);
        answer.setSession(this);
    }
}
