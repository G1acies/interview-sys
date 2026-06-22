package interview.guide.modules.interview.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 面试答案实体。
 * <p>
 * 对应数据库表 {@code interview_answers}，记录每道题的作答内容、评分和反馈。
 * <p>
 * 设计要点：
 * <ul>
 *   <li><b>唯一约束</b> — (session_id, question_index) 联合唯一，确保一道题只能答一次。
 *       暂存答案用 upsert 语义（查不到就新建，查到就更新）。</li>
 *   <li><b>问题和答案都存</b> — question 字段冗余存储题目原文，即使会话被清理也能独立展示。</li>
 *   <li><b>keyPointsJson</b> — 参考答案的关键点，JSON 数组格式，如 {@code ["要点1", "要点2"]}。</li>
 *   <li><b>score 为 null</b> — 表示尚未评估（暂存答案时），评估后才会填入。</li>
 * </ul>
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "interview_answers",
    uniqueConstraints = {
        @UniqueConstraint(name = "uk_interview_answer_session_question",
            columnNames = {"session_id", "question_index"})
    },
    indexes = {
        @Index(name = "idx_interview_answer_session_question",
            columnList = "session_id,question_index")
    })
public class InterviewAnswerEntity {

    /** 主键（自增） */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 关联的面试会话（多对一） */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "session_id", nullable = false)
    private InterviewSessionEntity session;

    /** 问题索引（从 0 开始，对应 questionsJson 中的顺序） */
    @Column(name = "question_index")
    private Integer questionIndex;

    /** 题目原文（冗余存储，方便独立展示） */
    @Column(columnDefinition = "TEXT")
    private String question;

    /** 题目分类，如 "JAVA"、"MYSQL"、"PROJECT" */
    private String category;

    /** 用户作答内容 */
    @Column(columnDefinition = "TEXT")
    private String userAnswer;

    /** 本题得分（0-100），null 表示尚未评估 */
    private Integer score;

    /** AI 给出的作答反馈 */
    @Column(columnDefinition = "TEXT")
    private String feedback;

    /** 参考答案 */
    @Column(columnDefinition = "TEXT")
    private String referenceAnswer;

    /** 关键点列表（JSON 数组格式） */
    @Column(columnDefinition = "TEXT")
    private String keyPointsJson;

    /** 作答时间 */
    @Column(nullable = false)
    private LocalDateTime answeredAt;

    /** 持久化前自动设置作答时间 */
    @PrePersist
    protected void onCreate() {
        answeredAt = LocalDateTime.now();
    }

}
