package interview.guide.modules.voice.service;

import interview.guide.modules.llmprovider.model.LlmGlobalSettingEntity;
import interview.guide.modules.llmprovider.repository.LlmGlobalSettingRepository;
import interview.guide.modules.llmprovider.service.ApiKeyEncryptionService;
import interview.guide.modules.voice.service.speech.DashScopeAsrClient;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ASR（语音转文字）服务。
 * <p>
 * 负责连接上游语音识别服务（DashScope/阿里云智能语音交互），
 * 将用户的实时音频流转换为文本。
 * <p>
 * 架构：
 * <pre>
 * 前端麦克风 → audio 帧 → WebSocket Handler
 *   → VoiceAsrService.sendAudio(connectionId, data)
 *     → DashScopeAsrClient（Java 21 WebSocket）
 *       → DashScope ASR API（阿里云）
 *         ↓ 识别结果
 *       → AsrCallback.onResult(text, isFinal)
 *     → WebSocket Handler 转发 subtitle 给前端
 * </pre>
 * <p>
 * 连接管理：每个语音面试会话对应一个 {@link DashScopeAsrClient} 实例，
 * 存储在 {@link #activeConnections} 中，以 connectionId（= sessionId 字符串）为 key。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VoiceAsrService {

    private final LlmGlobalSettingRepository globalSettingRepository;
    private final ApiKeyEncryptionService encryptionService;

    /** 活跃的 ASR 连接映射表。key = 会话 ID 字符串（也是 connectionId），value = ASR 客户端 */
    private final Map<String, DashScopeAsrClient> activeConnections = new ConcurrentHashMap<>();

    /**
     * 创建 ASR WebSocket 连接。
     * <p>
     * 从 LlmGlobalSetting 读取 ASR URL、model、apiKey 等配置，建立到
     * DashScope 实时语音识别的 WebSocket 连接。
     * 如果未配置 ASR URL，使用 DashScope 默认地址。
     *
     * @param sessionId 语音面试会话 ID（同时也是 connectionId）
     * @param callback  识别结果回调
     * @return ASR 连接标识（即 sessionId 的字符串形式）
     */
    public String connectAsr(Long sessionId, AsrCallback callback) {
        // 读取 ASR 配置
        LlmGlobalSettingEntity settings = getSettings();
        String asrUrl = settings.getAsrUrl();
        String apiKey = encryptionService.decrypt(settings.getEncryptedAsrApiKey());
        String model = settings.getAsrModel() != null ? settings.getAsrModel() : "paraformer-realtime-v2";
        int sampleRate = settings.getAsrSampleRate() != null ? settings.getAsrSampleRate() : 16000;

        // ASR URL 默认值
        if (asrUrl == null || asrUrl.isBlank()) {
            asrUrl = "wss://dashscope.aliyuncs.com/api/v1/audio/asr/generate";
        }

        String connectionId = String.valueOf(sessionId);

        // 包装 callback，在 ASR 连接就绪时触发 onConnected
        DashScopeAsrClient.AsrCallback wrappedCallback = new DashScopeAsrClient.AsrCallback() {
            @Override
            public void onResult(String text, boolean isFinal) {
                callback.onResult(text, isFinal);
            }

            @Override
            public void onError(String message) {
                log.warn("ASR 错误: sessionId={}, error={}", sessionId, message);
                callback.onError(message);
                activeConnections.remove(connectionId);
            }

            @Override
            public void onConnected() {
                log.info("ASR 连接就绪: sessionId={}", sessionId);
            }
        };

        // 创建并连接 ASR 客户端
        DashScopeAsrClient client = new DashScopeAsrClient(
                asrUrl, apiKey, model, sampleRate, wrappedCallback);

        activeConnections.put(connectionId, client);
        client.connect();

        log.info("ASR 连接已创建: sessionId={}, url={}, model={}", sessionId, asrUrl, model);
        return connectionId;
    }

    /**
     * 发送音频数据到 ASR 服务。
     *
     * @param connectionId ASR 连接标识（即 sessionId 字符串）
     * @param audioData    PCM 音频数据（Base64 编码，16kHz 16bit Int16）
     * @param timestamp    时间戳（用于日志追踪）
     */
    public void sendAudio(String connectionId, String audioData, long timestamp) {
        DashScopeAsrClient client = activeConnections.get(connectionId);
        if (client == null) {
            log.warn("ASR 连接不存在，丢弃音频: connectionId={}", connectionId);
            return;
        }
        client.sendAudio(audioData);
    }

    /**
     * 通知 ASR 服务语音结束，发送 finish 命令。
     *
     * @param connectionId ASR 连接标识
     */
    public void finishAsr(String connectionId) {
        DashScopeAsrClient client = activeConnections.get(connectionId);
        if (client != null) {
            client.finish();
        }
    }

    /**
     * 关闭 ASR 连接。
     *
     * @param connectionId ASR 连接标识
     */
    public void disconnectAsr(String connectionId) {
        DashScopeAsrClient client = activeConnections.remove(connectionId);
        if (client != null) {
            client.close();
            log.info("ASR 连接已关闭: connectionId={}", connectionId);
        }
    }

    /**
     * 应用关闭时清理所有 ASR 连接。
     */
    @PreDestroy
    public void cleanup() {
        activeConnections.forEach((id, client) -> {
            try {
                client.close();
            } catch (Exception e) {
                // 忽略
            }
        });
        activeConnections.clear();
        log.info("所有 ASR 连接已清理");
    }

    private LlmGlobalSettingEntity getSettings() {
        return globalSettingRepository.findAll().stream().findFirst()
                .orElseGet(() -> {
                    LlmGlobalSettingEntity s = new LlmGlobalSettingEntity();
                    s.setDefaultProvider("dashscope");
                    return globalSettingRepository.save(s);
                });
    }

    /**
     * ASR 识别结果回调接口。
     */
    public interface AsrCallback {
        void onResult(String text, boolean isFinal);
        void onError(String message);
    }
}
