package interview.guide.modules.voice.websocket;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.voice.model.VoiceInterviewMessageEntity;
import interview.guide.modules.voice.model.VoiceInterviewSessionEntity;
import interview.guide.modules.voice.model.VoiceInterviewSessionEntity.VoiceSessionStatus;
import interview.guide.modules.voice.repository.VoiceInterviewMessageRepository;
import interview.guide.modules.voice.repository.VoiceInterviewSessionRepository;
import interview.guide.modules.voice.service.VoiceAsrService;
import interview.guide.modules.voice.service.VoiceTtsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 语音面试 WebSocket Handler。
 * <p>
 * 这是语音面试的"中央交换机"——所有实时双向通信都经过这里。
 * 前端建立 WebSocket 连接后，通过本 Handler 完成：
 * <p>
 * ┌──────────────────────────────────────────────────────────────────┐
 * │                     消息流总图                                     │
 * ├──────────────────────────────────────────────────────────────────┤
 * │                                                                  │
 * │  前端麦克风 ──audio──→  Handler ──→  VoiceAsrService(ASR)         │
 * │                                  │                               │
 * │                                  ↓ (识别结果)                     │
 * │                    Handler ──subtitle──→ 前端显示实时字幕          │
 * │                                  │                               │
 * │                             是最终结果？                           │
 * │                                  ↓ 是                             │
 * │                    Handler ──→  ChatClient(LLM)                  │
 * │                                  │                               │
 * │                                  ↓ (AI 回复文本)                  │
 * │                    Handler ──→  VoiceTtsService(TTS)             │
 * │                                  │                               │
 * │                                  ↓ (合成语音分块)                 │
 * │                    Handler ──audio_chunk──→ 前端播放              │
 * │                                                                  │
 * │  前端按钮 ──control:submit──→  Handler ──→ LLM(同上)              │
 * │                                                                  │
 * └──────────────────────────────────────────────────────────────────┘
 * <p>
 * 状态管理：
 * <ul>
 *   <li>每个语音面试会话对应一个 {@link WebSocketSession}，以 sessionId 为键存储在 {@link #activeSessions} 中</li>
 *   <li>连接建立时校验会话存在且状态为 IN_PROGRESS</li>
 *   <li>连接断开时自动清理，不阻塞后续重连</li>
 * </ul>
 * <p>
 * TODO: 当前 ASR/TTS 集成尚未实现，音频消息仅打印日志。
 *       需要接入后的关键修改点已在代码中用 TODO:ASR 和 TODO:TTS 标记。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VoiceInterviewWebSocketHandler extends TextWebSocketHandler {

    private final VoiceInterviewSessionRepository sessionRepository;
    private final VoiceInterviewMessageRepository messageRepository;
    private final ChatClient.Builder chatClientBuilder;
    private final ObjectMapper objectMapper;

    private final VoiceAsrService asrService;
    private final VoiceTtsService ttsService;

    /**
     * 活跃的 WebSocket 连接映射表。
     * <p>
     * key = 语音面试会话 ID（Long），value = 该会话对应的 WebSocket 连接。
     * 使用 ConcurrentHashMap 保证并发安全，因为多个前端实例可能同时连接。
     * <p>
     * 设计决策：一个语音面试会话同一时刻只允许一个 WebSocket 连接。
     * 如果已有连接存在，旧连接会被主动关闭（防止"一人面试多人听"的混乱局面）。
     */
    private final Map<Long, WebSocketSession> activeSessions = new ConcurrentHashMap<>();

    /**
     * ASR 连接映射表（sessionId → ASR connectionId）。
     * 用于在收到音频帧时快速找到对应的 ASR  WebSocket 连接。
     */
    private final Map<Long, String> asrConnections = new ConcurrentHashMap<>();

    // ==================================================================
    // 连接生命周期回调
    // ==================================================================

    /**
     * WebSocket 连接建立后调用。
     * <p>
     * 执行步骤：
     * <ol>
     *   <li>从 URL 路径 {@code /ws/voice-interview/{sessionId}} 提取 sessionId</li>
     *   <li>从数据库查找对应的 {@link VoiceInterviewSessionEntity}</li>
     *   <li>校验会话存在且状态为 IN_PROGRESS（已暂停或已结束的会话拒绝连接）</li>
     *   <li>如果已有同名连接存在，关闭旧连接（防重复）</li>
     *   <li>将新连接存入 {@link #activeSessions}</li>
     *   <li>发送 {@code control: "asr_ready"} 通知前端可以开始说话</li>
     * </ol>
     * <p>
     * 异常处理：
     * <ul>
     *   <li>会话不存在 → 返回 4001 关闭码 + 错误消息</li>
     *   <li>会话已暂停/结束 → 返回 4002 关闭码 + 提示消息</li>
     *   <li>所有校验失败都不会保存连接，确保 {@link #activeSessions} 中只有合法连接</li>
     * </ul>
     */
    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        Long sessionId = extractSessionId(session);
        if (sessionId == null) {
            closeWithError(session, 4000, "无效的会话 ID");
            return;
        }

        log.info("WebSocket 连接建立: sessionId={}, remote={}", sessionId, session.getRemoteAddress());

        // 1. 校验会话存在
        Optional<VoiceInterviewSessionEntity> entityOpt = sessionRepository.findById(sessionId);
        if (entityOpt.isEmpty()) {
            closeWithError(session, 4001, "会话不存在: " + sessionId);
            return;
        }

        VoiceInterviewSessionEntity entity = entityOpt.get();

        // 2. 校验会话状态 —— 只有 IN_PROGRESS 才能进行语音通话
        if (entity.getStatus() != VoiceSessionStatus.IN_PROGRESS) {
            String msg = entity.getStatus() == VoiceSessionStatus.PAUSED
                    ? "会话已暂停，请先恢复面试"
                    : "会话已结束，无法继续面试";
            closeWithError(session, 4002, msg);
            return;
        }

        // 3. 如果已有同名连接，关闭旧连接
        WebSocketSession existing = activeSessions.get(sessionId);
        if (existing != null && existing.isOpen()) {
            log.warn("检测到重复连接，关闭旧连接: sessionId={}", sessionId);
            closeWithError(existing, 4003, "新的连接已建立，此连接将被关闭");
        }

        // 4. 保存新连接
        activeSessions.put(sessionId, session);

        // 5. 建立 ASR WebSocket 连接（异步连接，连接成功后前端会收到 asr_ready）
        try {
            String asrConnectionId = asrService.connectAsr(sessionId, new VoiceAsrService.AsrCallback() {
                @Override
                public void onResult(String text, boolean isFinal) {
                    // ASR 识别结果 → 通过 WebSocket 转发 subtitle 给前端
                    sendMessage(session, createSubtitleMessage(text, isFinal));

                    if (isFinal && !text.isBlank()) {
                        log.info("ASR 最终结果，自动提交回答: sessionId={}, text={}",
                                sessionId, truncate(text, 100));
                        // isFinal 时自动触发 submit
                        sendMessage(session, createControlMessage("asr_ready", ""));
                    }
                }

                @Override
                public void onError(String message) {
                    log.warn("ASR 错误: sessionId={}, error={}", sessionId, message);
                    sendMessage(session, createErrorMessage("语音识别异常: " + message));
                }
            });
            asrConnections.put(sessionId, asrConnectionId);
            log.info("ASR 连接已建立: sessionId={}, connectionId={}", sessionId, asrConnectionId);
        } catch (Exception e) {
            log.warn("ASR 连接失败（文字模式可用）: sessionId={}, error={}", sessionId, e.getMessage());
            // ASR 失败不阻断——前端仍可使用文字输入
            sendMessage(session, createControlMessage("asr_ready", "语音识别暂不可用，请使用文字输入"));
        }
    }

    /**
     * 收到 WebSocket 文本消息时调用。
     * <p>
     * 这是整个语音面试的核心方法——解析前端发来的 JSON 消息，根据 {@code type} 字段分派处理。
     * <p>
     * 消息格式（来自前端 {@code WebSocketMessage}）：
     * <pre>
     * audio 消息：  { "type": "audio",  "data": "base64 PCM",  "timestamp": 123456 }
     * control 消息：{ "type": "control", "action": "start|submit|stop", "data": {...} }
     * </pre>
     * <p>
     * 分派逻辑：
     * <ul>
     *   <li>{@code audio} → 转发给 ASR 服务处理（TODO:ASR 接入后实现）</li>
     *   <li>{@code control} → 根据 action 走不同的控制流程</li>
     *   <li>未知类型 → 返回 error 消息给前端</li>
     * </ul>
     */
    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        Long sessionId = extractSessionId(session);
        if (sessionId == null) return;

        try {
            // 解析 JSON 消息
            JsonNode root = objectMapper.readTree(message.getPayload());
            String type = root.path("type").asText("");

            log.debug("收到 WebSocket 消息: sessionId={}, type={}", sessionId, type);

            switch (type) {
                case "audio" -> handleAudioMessage(sessionId, session, root);
                case "control" -> handleControlMessage(sessionId, session, root);
                default -> sendMessage(session, createErrorMessage("未知消息类型: " + type));
            }

        } catch (Exception e) {
            log.error("解析 WebSocket 消息失败: sessionId={}, error={}", sessionId, e.getMessage(), e);
            sendMessage(session, createErrorMessage("消息格式错误: " + e.getMessage()));
        }
    }

    /**
     * WebSocket 连接关闭时调用。
     * <p>
     * 清理 {@link #activeSessions} 中的记录。
     * 注意：前端在"暂停面试"时也会主动关闭连接，此处不做额外业务处理。
     * 暂停、结束等操作通过 REST API 完成。
     */
    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        Long sessionId = extractSessionId(session);
        if (sessionId == null) return;

        log.info("WebSocket 连接关闭: sessionId={}, code={}, reason={}",
                sessionId, status.getCode(), status.getReason());

        // 只清理本连接（避免误删其他同名连接）
        activeSessions.remove(sessionId, session);

        // 关闭对应的 ASR 连接
        String asrConnectionId = asrConnections.remove(sessionId);
        if (asrConnectionId != null) {
            asrService.disconnectAsr(asrConnectionId);
            log.debug("ASR 连接已关闭: sessionId={}", sessionId);
        }
    }

    /**
     * WebSocket 传输层异常时调用。
     */
    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        Long sessionId = extractSessionId(session);
        log.error("WebSocket 传输异常: sessionId={}, error={}", sessionId, exception.getMessage(), exception);
        // 异常时自动关闭连接，由 afterConnectionClosed 处理清理
    }

    // ==================================================================
    // 消息处理
    // ==================================================================

    /**
     * 处理音频消息（前端麦克风流）——转发给 ASR 服务做实时语音识别。
     * <p>
     * 前端每 200ms 发送一包 PCM 音频数据（16kHz, Int16, Base64 编码）。
     * 音频数据通过 {@link VoiceAsrService#sendAudio} 转发给 DashScope ASR 服务，
     * ASR 的识别结果通过回调返回，本 Handler 再将结果显示为实时字幕。
     * <p>
     * 完整链路：
     * <pre>
     * 前端麦克风 → audio 帧
     *   → handleAudioMessage()
     *     → asrService.sendAudio(connectionId, base64Data, timestamp)
     *       → DashScope ASR WebSocket
     *         ↓ 识别结果回调
     *       → AsrCallback.onResult(text, isFinal)
     *         → sendMessage(session, createSubtitleMessage(text, isFinal))
     *           → 前端 RealtimeSubtitle 展示
     * </pre>
     *
     * @param sessionId 语音面试会话 ID
     * @param session   WebSocket 会话（用于回传消息）
     * @param root      消息 JSON 根节点
     */
    private void handleAudioMessage(Long sessionId, WebSocketSession session, JsonNode root) {
        String audioData = root.path("data").asText();
        long timestamp = root.path("timestamp").asLong(0);

        if (audioData == null || audioData.isEmpty()) {
            sendMessage(session, createErrorMessage("音频数据为空"));
            return;
        }

        // 查找 ASR 连接并转发音频
        String connectionId = asrConnections.get(sessionId);
        if (connectionId == null) {
            // ASR 未就绪时忽略音频（前端在 isAiSpeaking 或 !isAsrReady 时不会发音频）
            return;
        }

        asrService.sendAudio(connectionId, audioData, timestamp);
    }

    /**
     * 处理控制消息。
     * <p>
     * 前端通过控制消息发送指令，如提交回答、开始/停止录音等。
     * <p>
     * 支持的 action：
     * <ul>
     *   <li><b>submit</b> — 用户提交回答文本。触发 LLM 生成面试官回复。</li>
     *   <li><b>start</b> — 开始录音/面试（预留，当前建连即开始）</li>
     *   <li><b>stop</b> — 停止录音（预留）</li>
     *   <li>其他 — 返回不受支持的提示</li>
     * </ul>
     */
    private void handleControlMessage(Long sessionId, WebSocketSession session, JsonNode root) {
        String action = root.path("action").asText("");
        JsonNode data = root.path("data");

        log.info("收到控制消息: sessionId={}, action={}", sessionId, action);

        switch (action) {
            case "submit" -> handleSubmit(sessionId, session, data);
            case "start" -> handleStart(sessionId, session);
            case "stop" -> handleStop(sessionId, session);
            default -> sendMessage(session,
                    createControlMessage("unsupported", "不支持的控制指令: " + action));
        }
    }

    // ==================================================================
    // 控制消息子处理
    // ==================================================================

    /**
     * 处理 {@code action: "submit"} — 用户提交文字回答。
     * <p>
     * 前端在用户说完话（手动点击"提交回答"按钮）时发送此消息，
     * 携带用户 ASR 识别的最终文本或手动输入的文本。
     * <p>
     * 处理流程：
     * <ol>
     *   <li>保存用户消息到数据库（user_text / sequenceNum递增）</li>
     *   <li>发送 {@code control: "asr_ready"} 解锁麦克风</li>
     *   <li>调用 LLM 生成面试官回复（基于当前会话上下文和历史消息）</li>
     *   <li>将 AI 回复通过 text 消息推送给前端</li>
     *   <li>保存 AI 消息到数据库</li>
     *   <li>更新会话的消息计数</li>
     * </ol>
     * <p>
     * TODO: 当前 LLM 调用为同步模式。长回复场景应改为：
     *   <ol>
     *     <li>调用 ChatClient 的 stream() 方法流式输出 text(final=false) → text(final=true)</li>
     *     <li>完整文本收到后，触发 TTS 合成</li>
     *   </ol>
     *
     * @param sessionId 语音面试会话 ID
     * @param session   WebSocket 会话
     * @param data      控制消息的 data 字段（含 { text: "用户回答" }）
     */
    private void handleSubmit(Long sessionId, WebSocketSession session, JsonNode data) {
        // 1. 提取用户文本
        String userText = data.path("text").asText("");
        if (userText.isBlank()) {
            sendMessage(session, createErrorMessage("提交内容不能为空"));
            return;
        }

        log.info("用户提交回答: sessionId={}, text={}", sessionId, truncate(userText, 100));

        // 2. 更新会话消息计数并获取下一个序号
        VoiceInterviewSessionEntity entity = sessionRepository.findById(sessionId).orElse(null);
        if (entity == null) {
            sendMessage(session, createErrorMessage("会话不存在"));
            return;
        }
        int nextSeq = entity.getMessageCount() != null ? entity.getMessageCount() : 0;

        // 3. 保存用户消息到数据库
        VoiceInterviewMessageEntity userMsg = new VoiceInterviewMessageEntity();
        userMsg.setSessionId(sessionId);
        userMsg.setMessageType("user_text");
        userMsg.setPhase(entity.getCurrentPhase());
        userMsg.setUserRecognizedText(userText);
        userMsg.setTimestamp(LocalDateTime.now());
        userMsg.setSequenceNum(nextSeq);
        messageRepository.save(userMsg);
        log.debug("用户消息已持久化: sessionId={}, seq={}", sessionId, nextSeq);

        // 4. 解锁麦克风 — 通知前端可以开始下一轮说话
        sendMessage(session, createControlMessage("asr_ready", "可以继续回答"));

        // 5. 调用 LLM 生成面试官回复
        try {
            String aiReplyText = generateAiReply(sessionId, entity, userText);

            // 6. 先发送 AI 回复文本（让前端立即显示）
            sendMessage(session, createTextMessage(aiReplyText, true));

            // 7. 保存 AI 消息到数据库
            int aiSeq = nextSeq + 1;
            VoiceInterviewMessageEntity aiMsg = new VoiceInterviewMessageEntity();
            aiMsg.setSessionId(sessionId);
            aiMsg.setMessageType("ai_text");
            aiMsg.setPhase(entity.getCurrentPhase());
            aiMsg.setAiGeneratedText(aiReplyText);
            aiMsg.setTimestamp(LocalDateTime.now());
            aiMsg.setSequenceNum(aiSeq);
            messageRepository.save(aiMsg);
            log.debug("AI 回复已持久化: sessionId={}, seq={}", sessionId, aiSeq);

            // 8. 更新会话的消息计数
            entity.setMessageCount(aiSeq + 1);
            sessionRepository.save(entity);

            // 9. 异步调用 TTS 合成语音
            final String replyText = aiReplyText;
            final WebSocketSession wsSession = session;
            ttsService.synthesizeStream(replyText, new VoiceTtsService.TtsChunkCallback() {
                @Override
                public void onChunk(String base64Audio, int index, boolean isLast) {
                    // 每个音频分块 → audio_chunk 消息 → 前端播放
                    sendMessage(wsSession, createAudioChunkMessage(base64Audio, index, isLast));
                }

                @Override
                public void onComplete() {
                    log.debug("TTS 合成完成: sessionId={}", sessionId);
                    sendMessage(wsSession, createControlMessage("audio_complete", ""));
                }

                @Override
                public void onError(String message) {
                    // TTS 失败时音频不会播放，但文本已经发送，不影响体验
                    log.warn("TTS 合成失败（文本已发送）: sessionId={}, error={}", sessionId, message);
                }
            });

        } catch (Exception e) {
            log.error("AI 回复生成失败: sessionId={}, error={}", sessionId, e.getMessage(), e);
            sendMessage(session, createErrorMessage("AI 回复生成失败，请重试"));
        }
    }

    /**
     * 处理 {@code action: "start"} — 开始面试/录音。
     * <p>
     * 预留方法。当前建连即表示开始，无需额外操作。
     */
    private void handleStart(Long sessionId, WebSocketSession session) {
        log.info("开始语音面试: sessionId={}", sessionId);
        sendMessage(session, createControlMessage("asr_ready", "面试开始，请说话"));
    }

    /**
     * 处理 {@code action: "stop"} — 停止录音/处理。
     * <p>
     * 预留方法。当前前端通过 REST API 的 pause/end 来暂停/结束面试。
     */
    private void handleStop(Long sessionId, WebSocketSession session) {
        log.info("停止录音: sessionId={}", sessionId);
        // 停止当前正在进行的 ASR 或 TTS 操作
    }

    // ==================================================================
    // AI 面试官回复
    // ==================================================================

    /**
     * AI 系统提示词 — 语音面试官角色设定。
     * <p>
     * 要求 LLM 以口语化的面试官身份进行语音面试对话。
     * 每次用户提交回答后，LLM 根据用户回答给出评价、追问下一个问题或引导到下一环节。
     * <p>
     * 设计要点：
     * <ul>
     *   <li>口语化输出，适合 TTS 播放</li>
     *   <li>回复简短精炼（100 字以内），太长 TTS 播放体验差</li>
     *   <li>纯文本输出，不含 markdown 或 JSON</li>
     *   <li>根据当前阶段（INTRO/TECH/PROJECT/HR）调整提问风格</li>
     * </ul>
     */
    private static final String INTERVIEWER_SYSTEM_PROMPT = """
            你是一个专业的技术面试官，正在进行一场语音模拟面试。

            核心规则：
            1. 每次回复都要先简短评价用户的回答（1-2句），再提出下一个问题
            2. 语音面试场景，回复要口语化、自然，就像真人面试官在说话
            3. 每次回复控制在 50-100 字，太长了语音合成会让用户烦躁
            4. 不要使用 markdown 格式、列表、加粗等——纯文本输出
            5. 以面试官的第一人称说话（"我"、"我们"）
            
            当前面试阶段：{currentPhase}
            - INTRO: 自我介绍阶段，引导用户做简短自我介绍
            - TECH: 技术问答阶段，考察技术深度
            - PROJECT: 项目深挖阶段，追问项目细节
            - HR: HR 综合问题
            
            面试方向：{skillName}
            难度级别：{difficulty}
            
            用户刚才的回答是：
            {userText}
            
            请输出你的回复（纯文本）：
            """;

    /**
     * 调用 LLM 生成面试官回复。
     * <p>
     * 根据当前会话的面试阶段、技能方向、难度和用户的回答文本，
     * 让 LLM 以面试官身份生成自然的语音回复。
     * <p>
     * 如果 AI 调用失败，返回降级文案。
     *
     * @param sessionId 语音面试会话 ID
     * @param entity    会话实体（用于读取阶段、技能、难度等信息）
     * @param userText  用户本次的回答文本
     * @return AI 面试官回复文本
     */
    private String generateAiReply(Long sessionId, VoiceInterviewSessionEntity entity, String userText) {
        String skillName = entity.getSkillId();
        String difficulty = entity.getDifficulty() != null ? entity.getDifficulty() : "mid";
        String currentPhase = entity.getCurrentPhase() != null ? entity.getCurrentPhase() : "TECH";

        try {
            ChatClient chatClient = chatClientBuilder.build();

            String response = chatClient.prompt()
                    .user(u -> u.text("""
                            你是一个专业的技术面试官，正在进行一场语音模拟面试。

                            当前面试阶段：{currentPhase}
                            面试方向：{skillName}
                            难度级别：{difficulty}

                            用户刚才的回答是：{userText}

                            请以面试官的身份，用口语化的方式回复。先简短评价用户的回答，再提出下一个问题。
                            回复要简短（50-100字），纯文本，不要任何格式标记。
                            """)
                            .param("currentPhase", currentPhase)
                            .param("skillName", skillName)
                            .param("difficulty", difficulty)
                            .param("userText", userText))
                    .call()
                    .content();

            if (response != null && !response.isBlank()) {
                return response.strip();
            }

            log.warn("AI 返回空回复: sessionId={}", sessionId);

        } catch (Exception e) {
            log.error("AI 调用失败: sessionId={}, error={}", sessionId, e.getMessage(), e);
        }

        // 降级回复
        return "好的，我明白了。让我想想下一个问题……嗯，你能详细说一下你在那个项目中的具体职责吗？";
    }

    // ==================================================================
    // 消息构造器 — 构造要发送给前端的 JSON 消息
    // ==================================================================

    /**
     * 构造 subtitle（实时字幕）消息。
     * <p>
     * 前端收到后显示在 {@code RealtimeSubtitle} 组件中。
     * isFinal=true 时表示该句话的最终识别结果，
     * 前端会将文本提交到对话记录中。
     */
    private String createSubtitleMessage(String text, boolean isFinal) {
        return jsonMessage("""
                {"type":"subtitle","text":%s,"isFinal":%b}
                """.formatted(jsonEscape(text), isFinal));
    }

    /**
     * 构造 text（AI 回复文本）消息。
     * <p>
     * final=true 表示这是完整的回复（流式模式时 final=false 表示中间片段）。
     * 前端收到后显示在 RealtimeSubtitle 中，AI 在说话时显示动画。
     */
    private String createTextMessage(String content, boolean isFinal) {
        return jsonMessage("""
                {"type":"text","content":%s,"final":%b}
                """.formatted(jsonEscape(content), isFinal));
    }

    /**
     * 构造 audio_chunk（音频分块）消息。
     * <p>
     * 前端收到后通过 AudioContext 解码并播放。
     * isLast=true 时前端会等待所有分块播放完毕后调用 finishAiPlayback()。
     */
    private String createAudioChunkMessage(String base64Audio, int index, boolean isLast) {
        return jsonMessage("""
                {"type":"audio_chunk","data":%s,"index":%d,"isLast":%b}
                """.formatted(jsonEscape(base64Audio), index, isLast));
    }

    /**
     * 构造 control（控制）消息。
     * <p>
     * 用于传递 asr_ready、audio_complete、pause_timeout 等带外状态。
     * 前端通过 onControl 回调处理。
     */
    private String createControlMessage(String action, String message) {
        return jsonMessage("""
                {"type":"control","action":"%s","message":%s}
                """.formatted(action, jsonEscape(message)));
    }

    /**
     * 构造 error（错误）消息。
     * <p>
     * 前端收到后在页面上显示错误提示。
     */
    private String createErrorMessage(String message) {
        return jsonMessage("""
                {"type":"error","message":%s}
                """.formatted(jsonEscape(message)));
    }

    /**
     * 将原始 JSON 字符串包装为格式化消息（仅用于调试日志）。
     * <p>
     * 实际发送时直接使用原始 JSON 字符串。
     */
    private String jsonMessage(String rawJson) {
        // 移除多余的空白（日志友好），实际消息不包含换行
        return rawJson.replaceAll("\\s+", " ");
    }

    /**
     * JSON 字符串转义——确保消息中的文本不会破坏 JSON 格式。
     * <p>
     * 对 "、\、换行等特殊字符进行转义，防止 LLM 回复中的特殊字符导致消息解析失败。
     */
    private String jsonEscape(String text) {
        if (text == null) return "\"\"";
        return "\"" + text
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t")
                .replace("\b", "\\b")
                .replace("\f", "\\f")
                + "\"";
    }

    // ==================================================================
    // 工具方法
    // ==================================================================

    /**
     * 发送文本消息到前端。
     * <p>
     * 如果会话已关闭，静默忽略（避免在清理过程中抛异常）。
     *
     * @param session 目标 WebSocket 会话
     * @param message JSON 字符串消息
     */
    private void sendMessage(WebSocketSession session, String message) {
        if (session == null || !session.isOpen()) return;
        try {
            synchronized (session) {
                session.sendMessage(new TextMessage(message));
            }
        } catch (IOException e) {
            log.error("发送 WebSocket 消息失败: remote={}, error={}",
                    session.getRemoteAddress(), e.getMessage());
        }
    }

    /**
     * 关闭连接并发送错误原因。
     * <p>
     * 关闭码使用 4000-4999 范围（表示应用层错误），
     * 区别于 1000（正常关闭）和 1001-1015（协议层错误）。
     *
     * @param session  要关闭的会话
     * @param code     自定义关闭码
     * @param reason   关闭原因（会发送给前端）
     */
    private void closeWithError(WebSocketSession session, int code, String reason) {
        try {
            if (session.isOpen()) {
                session.close(new CloseStatus(code, reason));
            }
        } catch (IOException e) {
            log.warn("关闭 WebSocket 连接失败: {}", e.getMessage());
        }
    }

    /**
     * 从 WebSocket 会话的 URI 路径中提取 sessionId。
     * <p>
     * 路径格式：{@code /ws/voice-interview/{sessionId}}
     * Spring 在注册 Handler 时使用 URI 模板变量 {@code {sessionId}}，
     * 可通过 {@link java.util.Map} 获取。
     * <p>
     * 获取方式：通过会话的属性（attributes）中查找。
     * Spring 在握手阶段会将 URI 模板变量解析后放入 session attributes。
     */
    private Long extractSessionId(WebSocketSession session) {
        try {
            // 从 URI 路径中提取最后一段作为 sessionId
            // 路径格式: /ws/voice-interview/123
            String path = session.getUri() != null ? session.getUri().getPath() : "";
            if (path == null || path.isEmpty()) return null;

            String[] segments = path.split("/");
            String lastSegment = segments[segments.length - 1];
            return Long.parseLong(lastSegment);
        } catch (NumberFormatException e) {
            log.warn("无法从 URI 路径提取 sessionId: uri={}", session.getUri());
            return null;
        }
    }

    /**
     * 截断长文本用于日志显示。
     */
    private String truncate(String text, int maxLen) {
        if (text == null) return "null";
        return text.length() <= maxLen ? text : text.substring(0, maxLen) + "...";
    }
}
