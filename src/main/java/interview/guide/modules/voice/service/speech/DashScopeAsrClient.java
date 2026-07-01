package interview.guide.modules.voice.service.speech;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.util.Base64;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * DashScope（阿里云）实时语音识别（ASR）的 WebSocket 客户端。
 * <p>
 * 基于 DashScope 实时语音识别 WebSocket API 实现，使用 Java 21 内置的
 * {@link java.net.http.WebSocket} 客户端（无需额外依赖）。
 * <p>
 * DashScope ASR 协议概览：
 * <pre>
 * 1. 连接    →  wss://dashscope.aliyuncs.com/api/v1/audio/asr/generate
 *              Header: Authorization: Bearer {apiKey}
 *
 * 2. 发送开始 →  JSON 帧：
 *   {"header":{"action":"run-task","task_id":"xxx"},
 *    "payload":{"task_group":"audio","task":"asr","function":"Transcription",
 *      "model":"paraformer-realtime-v2","input":{},
 *      "parameters":{"format":"pcm","sample_rate":16000}}}
 *
 * 3. 音频流  →  二进制帧（PCM 16kHz 16bit 小端）
 *
 * 4. 接收结果 →  JSON 帧：
 *   {"header":{"action":"transcription-result","status":20000000},
 *    "payload":{"result":{"transcription":{"text":"你好","is_final":false}}}}
 *
 * 5. 发送结束 →  JSON 帧：
 *   {"header":{"action":"finish-task","task_id":"xxx"}}
 *
 * 6. 关闭    →  服务端返回 finish 后关闭连接
 * </pre>
 * <p>
 * 使用方式：
 * <pre>
 * DashScopeAsrClient client = new DashScopeAsrClient(asrUrl, apiKey, model, sampleRate, callback);
 * client.connect();               // 建立连接
 * client.sendAudio(audioData);    // 发送音频（可多次调用）
 * client.finish();                // 发送结束信号
 * client.close();                 // 关闭连接
 * </pre>
 */
@Slf4j
public class DashScopeAsrClient {

    private final String url;
    private final String apiKey;
    private final String model;
    private final int sampleRate;
    private final AsrCallback callback;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private HttpClient httpClient;
    private WebSocket webSocket;
    private String taskId;
    private volatile boolean connected = false;
    private final AtomicBoolean finished = new AtomicBoolean(false);
    private final CountDownLatch connectLatch = new CountDownLatch(1);

    /**
     * ASR 识别结果回调。
     */
    public interface AsrCallback {
        void onResult(String text, boolean isFinal);
        void onError(String message);
        void onConnected();
    }

    public DashScopeAsrClient(String url, String apiKey, String model, int sampleRate, AsrCallback callback) {
        this.url = url;
        this.apiKey = apiKey;
        this.model = model != null ? model : "paraformer-realtime-v2";
        this.sampleRate = sampleRate > 0 ? sampleRate : 16000;
        this.callback = callback;
        this.taskId = java.util.UUID.randomUUID().toString().replace("-", "");
    }

    /**
     * 建立 WebSocket 连接并发送 start 命令。
     * 阻塞等待直到连接就绪或超时。
     */
    public void connect() {
        try {
            httpClient = HttpClient.newBuilder()
                    .build();

            // 构造 WebSocket，带上 Authorization header
            webSocket = httpClient.newWebSocketBuilder()
                    .header("Authorization", "Bearer " + apiKey)
                    .buildAsync(URI.create(url), new AsrWebSocketListener())
                    .get(10, TimeUnit.SECONDS);

            // 等待 start 命令发送完成
            boolean ready = connectLatch.await(10, TimeUnit.SECONDS);
            if (!ready) {
                callback.onError("ASR 连接超时");
            }
        } catch (Exception e) {
            log.error("ASR 连接失败: url={}, error={}", url, e.getMessage());
            callback.onError("ASR 连接失败: " + e.getMessage());
        }
    }

    /**
     * 发送一帧 PCM 音频数据到 ASR 服务（Base64 编码输入）。
     */
    public void sendAudio(String base64Audio) {
        if (webSocket == null || finished.get()) return;
        try {
            byte[] audioBytes = Base64.getDecoder().decode(base64Audio);
            webSocket.sendBinary(ByteBuffer.wrap(audioBytes), true);
        } catch (Exception e) {
            log.warn("发送音频到 ASR 失败: {}", e.getMessage());
        }
    }

    /**
     * 发送 finish 命令通知 ASR 服务语音结束。
     */
    public void finish() {
        if (finished.compareAndSet(false, true)) {
            try {
                ObjectNode finishMsg = objectMapper.createObjectNode();
                ObjectNode header = finishMsg.putObject("header");
                header.put("action", "finish-task");
                header.put("task_id", taskId);
                webSocket.sendText(objectMapper.writeValueAsString(finishMsg), true);
            } catch (Exception e) {
                log.warn("发送 finish 命令失败: {}", e.getMessage());
            }
        }
    }

    /**
     * 关闭 WebSocket 连接。
     */
    public void close() {
        finish();
        if (webSocket != null) {
            try {
                webSocket.sendClose(1000, "ok");
            } catch (Exception e) {
                // 关闭异常忽略
            }
        }
    }

    // ==================================================================
    // WebSocket 事件监听器
    // ==================================================================

    /**
     * Java 21 WebSocket 的事件监听器。
     * 处理 DashScope ASR 协议的消息收发。
     */
    private class AsrWebSocketListener implements WebSocket.Listener {

        private final StringBuilder jsonBuffer = new StringBuilder();
        /** DashScope 返回的增量文本（用于拼装 isFinal 前的中间结果） */
        private final AtomicReference<String> incrementalText = new AtomicReference<>("");

        @Override
        public void onOpen(WebSocket webSocket) {
            log.info("ASR WebSocket 已打开: taskId={}, url={}", taskId, url);
            sendStartCommand(webSocket);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            jsonBuffer.append(data);
            if (!last) return WebSocket.Listener.super.onText(webSocket, data, last);

            String json = jsonBuffer.toString();
            jsonBuffer.setLength(0);

            try {
                JsonNode root = objectMapper.readTree(json);
                String action = root.path("header").path("action").asText("");
                int status = root.path("header").path("status").asInt(0);

                // 任务启动成功
                if ("run-task".equals(action) && status == 20000000) {
                    connected = true;
                    connectLatch.countDown();
                    callback.onConnected();
                    log.info("ASR 任务已启动: taskId={}", taskId);
                    return WebSocket.Listener.super.onText(webSocket, data, last);
                }

                // 任务启动失败
                if ("run-task".equals(action)) {
                    String msg = root.path("header").path("message").asText("ASR 任务启动失败");
                    callback.onError(msg);
                    connectLatch.countDown();
                    return WebSocket.Listener.super.onText(webSocket, data, last);
                }

                // 识别结果
                if ("transcription-result".equals(action) && status == 20000000) {
                    JsonNode transcription = root.path("payload").path("result").path("transcription");
                    String text = transcription.path("text").asText("");
                    boolean isFinal = transcription.path("is_final").asBoolean(false);

                    if (!text.isEmpty()) {
                        if (isFinal) {
                            // 最终结果：拼上之前积累的中间文本一起返回
                            String fullText = incrementalText.getAndSet("") + text;
                            callback.onResult(fullText, true);
                        } else {
                            // 中间结果：暂存，等 isFinal 时再拼完整
                            incrementalText.set(text);
                            callback.onResult(text, false);
                        }
                    }
                    return WebSocket.Listener.super.onText(webSocket, data, last);
                }

                // 任务完成
                if ("finished".equals(action) || "finish-task".equals(action)) {
                    log.info("ASR 任务完成: taskId={}", taskId);
                    finished.set(true);
                }

            } catch (Exception e) {
                log.warn("解析 ASR 响应失败: json={}, error={}", json, e.getMessage());
            }

            return WebSocket.Listener.super.onText(webSocket, data, last);
        }

        @Override
        public CompletionStage<?> onBinary(WebSocket webSocket, ByteBuffer data, boolean last) {
            // DashScope ASR 一般不会在 binary 帧中返回数据（结果走 text 帧）
            return WebSocket.Listener.super.onBinary(webSocket, data, last);
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            log.info("ASR WebSocket 已关闭: taskId={}, code={}, reason={}",
                    taskId, statusCode, reason);
            finished.set(true);
            return WebSocket.Listener.super.onClose(webSocket, statusCode, reason);
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            log.error("ASR WebSocket 错误: taskId={}, error={}", taskId, error.getMessage());
            finished.set(true);
            callback.onError("ASR 连接异常: " + error.getMessage());
        }

        /**
         * 发送 ASR 任务启动命令（run-task）。
         * 格式为 DashScope 实时语音识别的标准 JSON。
         */
        private void sendStartCommand(WebSocket webSocket) {
            try {
                ObjectNode msg = objectMapper.createObjectNode();
                ObjectNode header = msg.putObject("header");
                header.put("action", "run-task");
                header.put("task_id", taskId);

                ObjectNode payload = msg.putObject("payload");
                payload.put("task_group", "audio");
                payload.put("task", "asr");
                payload.put("function", "Transcription");
                payload.put("model", model);
                payload.putObject("input");

                ObjectNode params = payload.putObject("parameters");
                params.put("format", "pcm");
                params.put("sample_rate", sampleRate);

                params.put("enable_intermediate_result", true);
                params.put("enable_punctuation", true);
                params.put("enable_voice_detection", true);

                String json = objectMapper.writeValueAsString(msg);
                webSocket.sendText(json, true);
                log.debug("ASR start 命令已发送: taskId={}, model={}", taskId, model);

            } catch (Exception e) {
                log.error("发送 ASR start 命令失败: {}", e.getMessage());
                callback.onError("ASR 启动失败: " + e.getMessage());
                connectLatch.countDown();
            }
        }
    }
}
