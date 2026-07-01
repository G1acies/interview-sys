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

/**
 * DashScope（阿里云）语音合成（TTS）的 WebSocket 客户端。
 * <p>
 * 基于 DashScope 流式语音合成 WebSocket API 实现，使用 Java 21 内置的
 * {@link java.net.http.WebSocket} 客户端（无需额外依赖）。
 * <p>
 * DashScope TTS 协议概览：
 * <pre>
 * 1. 连接    →  wss://dashscope.aliyuncs.com/api/v1/audio/tts/generate
 *              Header: Authorization: Bearer {apiKey}
 *
 * 2. 发送合成 →  JSON 帧：
 *   {"header":{"action":"run-task","task_id":"xxx"},
 *    "payload":{"task_group":"audio","task":"tts","function":"SpeechSynthesizer",
 *      "model":"cosyvoice-v1",
 *      "input":{"text":"要合成的文本"},
 *      "parameters":{"format":"wav","sample_rate":24000,"voice":"Cherry"}}}
 *
 * 3. 接收音频 →  二进制帧（WAV 格式音频数据）
 *    （可以收到多个二进制帧，每个帧是一个音频分块）
 *
 * 4. 接收完成 →  JSON 帧：
 *   {"header":{"action":"completed","status":20000000}}
 *
 * 5. 关闭
 * </pre>
 * <p>
 * 使用方式：
 * <pre>
 * DashScopeTtsClient client = new DashScopeTtsClient(url, apiKey, model, voice, sampleRate, callback);
 * client.synthesize(text);  // 发送合成请求，异步接收音频
 * client.waitForComplete(); // 等待合成完成
 * </pre>
 */
@Slf4j
public class DashScopeTtsClient {

    private final String url;
    private final String apiKey;
    private final String model;
    private final String voice;
    private final int sampleRate;
    private final TtsChunkCallback callback;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private HttpClient httpClient;
    private WebSocket webSocket;
    private String taskId;
    private final AtomicBoolean completed = new AtomicBoolean(false);
    private final CountDownLatch completeLatch = new CountDownLatch(1);
    private final AtomicInteger chunkIndex = new AtomicInteger(0);
    private String audioFormat;

    /**
     * TTS 流式分块回调。
     */
    public interface TtsChunkCallback {
        void onChunk(String base64Audio, int index, boolean isLast);
        void onComplete();
        void onError(String message);
    }

    public DashScopeTtsClient(String url, String apiKey, String model, String voice,
                               int sampleRate, TtsChunkCallback callback) {
        this.url = url;
        this.apiKey = apiKey;
        this.model = model != null ? model : "cosyvoice-v1";
        this.voice = voice != null ? voice : "Cherry";
        this.sampleRate = sampleRate > 0 ? sampleRate : 24000;
        this.callback = callback;
        this.taskId = java.util.UUID.randomUUID().toString().replace("-", "");
    }

    /**
     * 发送文本合成请求并开始接收音频。
     * 音频数据通过回调 {@link TtsChunkCallback#onChunk} 逐块返回。
     *
     * @param text 待合成的文本
     */
    public void synthesize(String text) {
        try {
            httpClient = HttpClient.newBuilder().build();

            webSocket = httpClient.newWebSocketBuilder()
                    .header("Authorization", "Bearer " + apiKey)
                    .buildAsync(URI.create(url), new TtsWebSocketListener())
                    .get(10, TimeUnit.SECONDS);

            // 发送合成任务命令
            ObjectNode msg = objectMapper.createObjectNode();
            ObjectNode header = msg.putObject("header");
            header.put("action", "run-task");
            header.put("task_id", taskId);

            ObjectNode payload = msg.putObject("payload");
            payload.put("task_group", "audio");
            payload.put("task", "tts");
            payload.put("function", "SpeechSynthesizer");
            payload.put("model", model);

            ObjectNode input = payload.putObject("input");
            input.put("text", text);

            ObjectNode params = payload.putObject("parameters");
            params.put("format", "wav");
            params.put("sample_rate", sampleRate);
            params.put("voice", voice);
            // 余弦声 v1 支持的音色: Cherry（女声）、Henry（男声）、Jenny、Zhiqing 等

            String json = objectMapper.writeValueAsString(msg);
            webSocket.sendText(json, true);
            log.info("TTS 合成请求已发送: taskId={}, model={}, voice={}, textLength={}",
                    taskId, model, voice, text.length());

        } catch (Exception e) {
            log.error("TTS 连接/发送失败: {}", e.getMessage());
            callback.onError("TTS 合成失败: " + e.getMessage());
        }
    }

    /**
     * 阻塞等待 TTS 合成完成。
     *
     * @param timeoutMs 超时时间（毫秒）
     * @return true 表示合成完成，false 表示超时
     */
    public boolean waitForComplete(long timeoutMs) {
        try {
            return completeLatch.await(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * 关闭连接。
     */
    public void close() {
        if (webSocket != null) {
            try {
                webSocket.sendClose(1000, "ok");
            } catch (Exception e) {
                // 忽略
            }
        }
    }

    // ==================================================================
    // WebSocket 事件监听器
    // ==================================================================

    /**
     * Java 21 WebSocket 的事件监听器，处理 DashScope TTS 协议。
     */
    private class TtsWebSocketListener implements WebSocket.Listener {

        private final StringBuilder jsonBuffer = new StringBuilder();

        @Override
        public void onOpen(WebSocket webSocket) {
            log.info("TTS WebSocket 已打开: taskId={}", taskId);
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

                if ("completed".equals(action) || "finish-task".equals(action)) {
                    log.info("TTS 合成完成: taskId={}", taskId);
                    completed.set(true);
                    completeLatch.countDown();
                    callback.onComplete();
                } else if (status != 20000000 && status != 0) {
                    String msg = root.path("header").path("message").asText("TTS 合成失败");
                    log.warn("TTS 错误: taskId={}, status={}, msg={}", taskId, status, msg);
                    callback.onError(msg);
                    completeLatch.countDown();
                }

            } catch (Exception e) {
                log.warn("解析 TTS 响应失败: json={}, error={}", json, e.getMessage());
            }

            return WebSocket.Listener.super.onText(webSocket, data, last);
        }

        @Override
        public CompletionStage<?> onBinary(WebSocket webSocket, ByteBuffer data, boolean last) {
            try {
                byte[] bytes = new byte[data.remaining()];
                data.get(bytes);

                // 编码为 Base64 返回给调用方
                String base64Audio = Base64.getEncoder().encodeToString(bytes);
                int index = chunkIndex.getAndIncrement();

                log.debug("TTS 收到音频分块: taskId={}, index={}, size={}bytes, last={}",
                        taskId, index, bytes.length, last);

                callback.onChunk(base64Audio, index, last);

                if (last) {
                    // 最后一个分块标记完成
                    completed.set(true);
                    completeLatch.countDown();
                    callback.onComplete();
                }

            } catch (Exception e) {
                log.warn("处理 TTS 音频分块失败: {}", e.getMessage());
            }

            return WebSocket.Listener.super.onBinary(webSocket, data, last);
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            log.info("TTS WebSocket 已关闭: taskId={}, code={}, reason={}",
                    taskId, statusCode, reason);
            if (!completed.get()) {
                completed.set(true);
                completeLatch.countDown();
                callback.onComplete();
            }
            return WebSocket.Listener.super.onClose(webSocket, statusCode, reason);
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            log.error("TTS WebSocket 错误: taskId={}, error={}", taskId, error.getMessage());
            callback.onError("TTS 连接异常: " + error.getMessage());
            completeLatch.countDown();
        }
    }
}
