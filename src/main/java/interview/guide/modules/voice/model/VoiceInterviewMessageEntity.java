package interview.guide.modules.voice.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 语音面试消息实体。
 * <p>
 * 对应数据库表 {@code voice_interview_messages}，记录语音面试中每一轮对话。
 * 包括用户的 ASR 识别文本、AI 回复文本，以及消息的阶段归属和顺序。
 * <p>
 * 注意：音频数据不持久化到数据库，仅在 WebSocket 通信中实时传输。
 * 此处只存储识别/生成的文本，用于历史回顾和评估。
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "voice_interview_messages", indexes = {
    @Index(name = "idx_voice_msg_session_seq", columnList = "session_id,sequence_num"),
    @Index(name = "idx_voice_msg_session_phase", columnList = "session_id,phase"),
})
public class VoiceInterviewMessageEntity {

    /** 主键（自增） */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 关联的语音面试会话 ID */
    @Column(name = "session_id", nullable = false)
    private Long sessionId;

    /** 消息类型：user_audio（用户语音）, user_text（用户手动输入）, ai_text（AI 回复文本）, system（系统消息） */
    @Column(name = "message_type", length = 20)
    private String messageType;

    /** 消息所属面试阶段：INTRO / TECH / PROJECT / HR */
    @Column(length = 20)
    private String phase;

    /** ASR 识别的用户文本（语音输入时由 ASR 生成，手动输入时为用户输入的文本） */
    @Column(name = "user_recognized_text", columnDefinition = "TEXT")
    private String userRecognizedText;

    /** AI 生成的回复文本 */
    @Column(name = "ai_generated_text", columnDefinition = "TEXT")
    private String aiGeneratedText;

    /** 消息时间戳 */
    @Column(nullable = false)
    private LocalDateTime timestamp;

    /** 消息序号（同一会话内递增，用于排序和去重） */
    @Column(name = "sequence_num")
    private Integer sequenceNum;

    @PrePersist
    protected void onCreate() {
        if (timestamp == null) {
            timestamp = LocalDateTime.now();
        }
    }
}
