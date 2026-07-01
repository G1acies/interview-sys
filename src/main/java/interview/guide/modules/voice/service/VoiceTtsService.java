package interview.guide.modules.voice.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * TTS（文字转语音）服务。
 * <p>
 * 负责将 AI 生成的面试回复文本合成为语音音频，返回给前端播放。
 * <p>
 * 职责边界：
 * <ul>
 *   <li><b>文本合成</b> — 调用 TTS API 将文本转为 PCM/MP3 音频</li>
 *   <li><b>流式输出</b> — 支持分块返回音频数据，让前端边合成边播放</li>
 *   <li><b>发音人选择</b> — 根据配置选择不同的音色</li>
 * </ul>
 * <p>
 * 支持两种输出模式：
 * <ul>
 *   <li>{@link #synthesize(String, TtsCallback)} — 完整合成后一次性返回</li>
 *   <li>{@link #synthesizeStream(String, TtsChunkCallback)} — 流式分块返回</li>
 * </ul>
 */
@Slf4j
@Service
public class VoiceTtsService {

    /**
     * 将文本合成为完整音频（一次性返回）。
     * <p>
     * 从 LlmGlobalSetting 读取 TTS 配置（model、voice、speechRate 等），
     * 调用 TTS API 进行合成。结果通过 callback 返回 Base64 编码的音频数据和文本。
     *
     * @param text     待合成的文本
     * @param callback 合成结果回调
     */
    public void synthesize(String text, TtsCallback callback) {
        // TODO: 调用 TTS API 合成音频 → callback.onResult(audioData, text)
        log.info("TTS 合成文本: length={}", text.length());
        throw new UnsupportedOperationException("TTS 服务尚未实现");
    }

    /**
     * 将文本合成为分块音频（流式返回）。
     * <p>
     * 适合长文本场景，让前端在收到第一个分块后即可开始播放。
     *
     * @param text     待合成的文本
     * @param callback 每个分块的回调
     */
    public void synthesizeStream(String text, TtsChunkCallback callback) {
        // TODO: 调用 TTS 流式 API → 逐块回调 callback.onChunk(data, index, isLast)
        log.info("TTS 流式合成文本: length={}", text.length());
        throw new UnsupportedOperationException("TTS 服务尚未实现");
    }

    /**
     * TTS 合成结果回调（一次性模式）。
     */
    public interface TtsCallback {
        /** 合成完成。audioData 为 Base64 编码的音频数据。 */
        void onResult(String audioData, String text);
        /** 合成出错 */
        void onError(String message);
    }

    /**
     * TTS 分块回调（流式模式）。
     */
    public interface TtsChunkCallback {
        /** 收到一个音频分块。isLast=true 表示这是最后一个分块。 */
        void onChunk(String audioData, int index, boolean isLast);
        /** 全部合成完成 */
        void onComplete();
        /** 合成出错 */
        void onError(String message);
    }
}
