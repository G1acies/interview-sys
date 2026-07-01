package interview.guide.modules.voice.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * ASR（语音转文字）服务。
 * <p>
 * 负责连接上游语音识别服务（如阿里云智能语音交互、OpenAI Whisper 等），
 * 将用户的实时音频流转换为文本。
 * <p>
 * 职责边界：
 * <ul>
 *   <li><b>建连</b> — 根据 ASR 配置建立 WebSocket 连接到 ASR 服务</li>
 *   <li><b>音频转发</b> — 将前端传来的 PCM 音频数据转发给 ASR 服务</li>
 *   <li><b>结果回调</b> — 接收 ASR 服务的识别结果并通过回调接口返回给调用方</li>
 *   <li><b>静音检测</b> — 使用服务端 VAD 判断用户是否说完</li>
 * </ul>
 * <p>
 * 本服务是底层集成层，不包含业务逻辑。语音面试的业务编排在
 * {@link VoiceInterviewService} 中完成。
 */
@Slf4j
@Service
public class VoiceAsrService {

    /**
     * 创建 ASR WebSocket 连接。
     * <p>
     * 从 LlmGlobalSetting 读取 ASR URL、model、apiKey 等配置，
     * 建立到 ASR 服务的 WebSocket 连接。
     *
     * @param sessionId      语音面试会话 ID
     * @param callback       识别结果回调（接收识别文本和是否终态）
     * @return ASR 连接标识（用于后续关闭）
     */
    public String connectAsr(Long sessionId, AsrCallback callback) {
        // TODO: 读取 ASR 配置 → 建立 WebSocket 连接 → 返回连接 ID
        log.info("创建 ASR 连接: sessionId={}", sessionId);
        throw new UnsupportedOperationException("ASR 服务尚未实现");
    }

    /**
     * 发送音频数据到 ASR 服务。
     *
     * @param connectionId ASR 连接标识
     * @param audioData    PCM 音频数据（Base64 编码）
     * @param timestamp     时间戳
     */
    public void sendAudio(String connectionId, String audioData, long timestamp) {
        // TODO: 将音频数据通过 WebSocket 发送给 ASR 服务
        log.debug("发送音频到 ASR: connectionId={}, size={}", connectionId, audioData.length());
        throw new UnsupportedOperationException("ASR 服务尚未实现");
    }

    /**
     * 关闭 ASR 连接。
     *
     * @param connectionId ASR 连接标识
     */
    public void disconnectAsr(String connectionId) {
        // TODO: 关闭 ASR WebSocket 连接
        log.info("关闭 ASR 连接: connectionId={}", connectionId);
        throw new UnsupportedOperationException("ASR 服务尚未实现");
    }

    /**
     * ASR 识别结果回调接口。
     */
    public interface AsrCallback {
        /** 收到识别结果。isFinal=true 表示这是该句话的最终识别结果。 */
        void onResult(String text, boolean isFinal);
        /** ASR 连接出错 */
        void onError(String message);
    }
}
