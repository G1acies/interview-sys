package interview.guide.modules.voice.service;

import interview.guide.modules.llmprovider.model.LlmGlobalSettingEntity;
import interview.guide.modules.llmprovider.repository.LlmGlobalSettingRepository;
import interview.guide.modules.llmprovider.service.ApiKeyEncryptionService;
import interview.guide.modules.voice.service.speech.DashScopeTtsClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * TTS（文字转语音）服务。
 * <p>
 * 负责将 AI 生成的面试回复文本合成为语音音频，返回给前端播放。
 * <p>
 * 架构：
 * <pre>
 * WebSocket Handler（收到 LLM 回复）
 *   → VoiceTtsService.synthesizeStream(text, callback)
 *     → DashScopeTtsClient（Java 21 WebSocket）
 *       → DashScope TTS API（阿里云 CosyVoice）
 *         ↓ 音频分块（Base64 WAV）
 *       → TtsChunkCallback.onChunk(data, index, isLast)
 *     → WebSocket Handler 转发 audio_chunk 给前端
 * </pre>
 * <p>
 * 支持两种模式：
 * <ul>
 *   <li>{@link #synthesize(String, TtsCallback)} — 完整合成后一次性回调，适合短文本</li>
 *   <li>{@link #synthesizeStream(String, TtsChunkCallback)} — 流式分块回调，适合长文本</li>
 * </ul>
 * <p>
 * TTS 配置通过 LlmGlobalSetting 管理，用户可在设置页配置：
 * <ul>
 *   <li>API Key（DashScope/阿里云）</li>
 *   <li>模型（默认 cosyvoice-v1）</li>
 *   <li>发音人（默认 Cherry，可选 Henry、Jenny 等）</li>
 *   <li>语速、音量等</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VoiceTtsService {

    private final LlmGlobalSettingRepository globalSettingRepository;
    private final ApiKeyEncryptionService encryptionService;

    /**
     * 将文本合成为完整音频（一次性返回）。
     * <p>
     * 使用 DashScopeTtsClient 建立 WebSocket 连接，发送文本，
     * 等待所有音频分块接收完成后，将完整音频通过回调返回。
     * 适合较短的面试问题（50 字以内）。
     *
     * @param text     待合成的文本
     * @param callback 合成结果回调
     */
    public void synthesize(String text, TtsCallback callback) {
        if (text == null || text.isBlank()) {
            callback.onError("合成文本不能为空");
            return;
        }

        StringBuilder fullAudio = new StringBuilder();
        int[] chunkCount = {0};

        synthesizeStream(text, new TtsChunkCallback() {
            @Override
            public void onChunk(String base64Audio, int index, boolean isLast) {
                fullAudio.append(base64Audio);
                chunkCount[0]++;
                if (isLast) {
                    callback.onResult(fullAudio.toString(), text);
                    log.debug("TTS 合成完成（一次性）: textLength={}, chunks={}",
                            text.length(), chunkCount[0]);
                }
            }

            @Override
            public void onComplete() {
                // 如果没收到任何分块但已完成，返回空（预防极端情况）
                if (chunkCount[0] == 0) {
                    callback.onResult("", text);
                }
            }

            @Override
            public void onError(String message) {
                callback.onError(message);
            }
        });
    }

    /**
     * 将文本合成为分块音频（流式返回）。
     * <p>
     * 适合长文本场景。前端在收到第一个 {@code audio_chunk} 后即可开始播放，
     * 实现"边合成边播放"的低延迟体验。
     * <p>
     * 注意：此方法 non-blocking，音频通过回调异步返回。
     *
     * @param text     待合成的文本
     * @param callback 每个分块的回调
     */
    public void synthesizeStream(String text, TtsChunkCallback callback) {
        if (text == null || text.isBlank()) {
            callback.onError("合成文本不能为空");
            return;
        }

        try {
            // 读取 TTS 配置
            LlmGlobalSettingEntity settings = getSettings();
            String ttsUrl = "wss://dashscope.aliyuncs.com/api/v1/audio/tts/generate";
            String apiKey = encryptionService.decrypt(settings.getEncryptedTtsApiKey());
            String model = settings.getTtsModel() != null ? settings.getTtsModel() : "cosyvoice-v1";
            String voice = settings.getTtsVoice() != null ? settings.getTtsVoice() : "Cherry";
            int sampleRate = settings.getTtsSampleRate() != null ? settings.getTtsSampleRate() : 24000;

            if (apiKey == null || apiKey.isBlank()) {
                callback.onError("TTS API Key 未配置，请在设置页中配置 TTS");
                return;
            }

            // 创建 DashScope TTS 客户端
            DashScopeTtsClient client = new DashScopeTtsClient(
                    ttsUrl, apiKey, model, voice, sampleRate,
                    new DashScopeTtsClient.TtsChunkCallback() {
                        @Override
                        public void onChunk(String base64Audio, int index, boolean isLast) {
                            callback.onChunk(base64Audio, index, isLast);
                        }

                        @Override
                        public void onComplete() {
                            callback.onComplete();
                        }

                        @Override
                        public void onError(String message) {
                            callback.onError(message);
                        }
                    });

            // 发送合成请求（WebSocket 连接 + 发送文本）
            client.synthesize(text);

            // 注意：这里的 client 引用被回调持有，GC 不会回收
            // 合成完成后通过回调通知调用方，调用方负责清理

        } catch (Exception e) {
            log.error("TTS 合成失败: {}", e.getMessage());
            callback.onError("TTS 合成失败: " + e.getMessage());
        }
    }

    /**
     * 获取全局设置记录。
     */
    private LlmGlobalSettingEntity getSettings() {
        return globalSettingRepository.findAll().stream().findFirst()
                .orElseGet(() -> {
                    LlmGlobalSettingEntity s = new LlmGlobalSettingEntity();
                    s.setDefaultProvider("dashscope");
                    return globalSettingRepository.save(s);
                });
    }

    /**
     * TTS 合成结果回调（一次性模式）。
     */
    public interface TtsCallback {
        void onResult(String audioData, String text);
        void onError(String message);
    }

    /**
     * TTS 分块回调（流式模式）。
     */
    public interface TtsChunkCallback {
        void onChunk(String base64Audio, int index, boolean isLast);
        void onComplete();
        void onError(String message);
    }
}
