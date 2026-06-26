package interview.guide.modules.llmprovider.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * LLM 全局设置实体。
 * <p>
 * 对应数据库 {@code llm_global_settings} 表。
 * 存储默认 Provider 以及语音 ASR/TTS 的配置。
 * 整个系统只有一条记录（id=1），启动时自动初始化。
 */
@Entity
@Table(name = "llm_global_settings")
@Getter
@Setter
@NoArgsConstructor
public class LlmGlobalSettingEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // ── 默认 Provider ──

    /** 默认聊天 Provider 的 providerId。 */
    @Column(length = 50)
    private String defaultProvider;

    /** 默认向量化 Provider 的 providerId。 */
    @Column(length = 50)
    private String defaultEmbeddingProvider;

    // ── ASR 配置（语音转文字） ──

    /** ASR WebSocket URL。 */
    @Column(length = 500)
    private String asrUrl;

    /** ASR 模型名称。 */
    @Column(length = 100)
    private String asrModel;

    /** 加密的 ASR API Key。 */
    @Column(length = 500)
    private String encryptedAsrApiKey;

    /** ASR 语言。 */
    @Column(length = 20)
    private String asrLanguage = "zh";

    /** 音频格式。 */
    @Column(length = 20)
    private String asrFormat = "pcm";

    /** 采样率。 */
    private Integer asrSampleRate = 16000;

    /** 是否启用 VAD 静音检测。 */
    private Boolean asrEnableTurnDetection = true;

    /** VAD 检测类型。 */
    @Column(length = 50)
    private String asrTurnDetectionType = "server_vad";

    /** VAD 检测阈值。 */
    private Float asrTurnDetectionThreshold = 0.0f;

    /** VAD 静音判断时长（毫秒）。 */
    private Integer asrTurnDetectionSilenceDurationMs = 2000;

    // ── TTS 配置（文字转语音） ──

    /** TTS 模型名称。 */
    @Column(length = 100)
    private String ttsModel;

    /** 加密的 TTS API Key。 */
    @Column(length = 500)
    private String encryptedTtsApiKey;

    /** 发音人。 */
    @Column(length = 50)
    private String ttsVoice = "Cherry";

    /** TTS 音频格式。 */
    @Column(length = 20)
    private String ttsFormat = "pcm";

    /** TTS 采样率。 */
    private Integer ttsSampleRate = 24000;

    /** TTS 模式。 */
    @Column(length = 20)
    private String ttsMode = "commit";

    /** 语言类型。 */
    @Column(length = 20)
    private String ttsLanguageType = "Chinese";

    /** 语速。 */
    private Float ttsSpeechRate = 1.0f;

    /** 音量 0~100。 */
    private Integer ttsVolume = 60;
}
