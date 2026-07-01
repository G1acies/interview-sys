package interview.guide.modules.voice.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.common.model.AsyncTaskStatus;
import interview.guide.modules.voice.stream.VoiceEvaluationStreamProducer;
import interview.guide.modules.interview.service.InterviewSkillService;
import interview.guide.modules.voice.dto.CreateVoiceSessionRequest;
import interview.guide.modules.voice.dto.PauseSessionRequest;
import interview.guide.modules.voice.dto.VoiceAnswerDetail;
import interview.guide.modules.voice.dto.VoiceEvaluationDetail;
import interview.guide.modules.voice.dto.VoiceEvaluationStatusResponse;
import interview.guide.modules.voice.dto.VoiceInterviewMessageDTO;
import interview.guide.modules.voice.dto.VoiceSessionMeta;
import interview.guide.modules.voice.dto.VoiceSessionResponse;
import interview.guide.modules.voice.model.VoiceInterviewMessageEntity;
import interview.guide.modules.voice.model.VoiceInterviewSessionEntity;
import interview.guide.modules.voice.model.VoiceInterviewSessionEntity.VoiceSessionStatus;
import interview.guide.modules.voice.repository.VoiceInterviewMessageRepository;
import interview.guide.modules.voice.repository.VoiceInterviewSessionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * 语音面试核心业务服务。
 * <p>
 * 负责语音面试会话的全生命周期管理，包括创建、暂停、恢复、结束、删除，
 * 以及消息记录查询和评估触发的调度。
 * <p>
 * 设计要点：
 * <ul>
 *   <li><b>状态机</b> — CREATED → IN_PROGRESS ⇄ PAUSED → COMPLETED → EVALUATED</li>
 *   <li><b>时长计算</b> — actualDuration 仅累计实际面试时间（扣除暂停时段）</li>
 *   <li><b>评估异步化</b> — 评估触发后立即返回，由 {@link VoiceEvaluationService} 异步执行</li>
 *   <li><b>WebSocket 托管</b> — 本服务不直接管理 WebSocket 连接，
 *       连接状态由 {@code VoiceInterviewWebSocketHandler} 维护</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VoiceInterviewService {

    private final VoiceInterviewSessionRepository sessionRepository;
    private final VoiceInterviewMessageRepository messageRepository;
    private final InterviewSkillService skillService;
    private final ChatClient.Builder chatClientBuilder;
    private final ObjectMapper objectMapper;

    private final VoiceEvaluationStreamProducer evaluationStreamProducer;

    // 子服务（后续实现时取消注释）
    // private final VoiceAsrService asrService;
    // private final VoiceTtsService ttsService;

    // ==================================================================
    // 常量
    // ==================================================================

    /**
     * AI 开场白系统提示词。
     * <p>
     * 要求 LLM 以面试官身份生成一段自然的口语开场白，用于语音面试的开始阶段。
     * 开场白包含面试官自我介绍、面试流程说明和阶段开启提示。
     * <p>
     * 设计要点：
     * <ul>
     *   <li>语气要求口语化、自然，适合语音合成播放</li>
     *   <li>根据开启的环节动态说明流程（INTRO/TECH/PROJECT/HR）</li>
     *   <li>输出纯文本，不含 markdown 标记</li>
     *   <li>长度控制在 200 字以内，避免 TTS 合成过长</li>
     * </ul>
     */
    private static final String OPENING_SYSTEM_PROMPT = """
            你是一个专业的技术面试官，正在进行一场语音模拟面试。

            请根据以下信息生成一段自然的口语开场白：

            面试方向：{skillName}
            难度级别：{difficulty}
            开启环节：{enabledPhases}

            要求：
            1. 语气亲切、自然、口语化，听起来像真人面试官在说话
            2. 包含：打招呼 → 自我介绍 → 简单说明今天的面试流程 → 进入第一环节
            3. 不要提到"AI"或"语音合成"等字眼，以真人面试官身份说话
            4. 纯文本输出，不需要任何格式标记或 JSON
            5. 长度控制在 100-200 字，简短有力
            6. 直接说出你的开场白，不要带"开场白："或引号

            示例风格（Java 后端，开启技术+项目）：
            "你好，欢迎参加今天的模拟面试。我是你的面试官，今天主要考察 Java 后端开发方向，
            我们会先聊聊技术基础，再深入挖掘你的项目经验。准备好了吗？那我们从第一个问题开始。"
            """;

    // ==================================================================
    // 会话 CRUD
    // ==================================================================

    /**
     * 创建新的语音面试会话。
     * <p>
     * 核心流程（6 步）：
     * <ol>
     *   <li><b>校验 skillId</b> — 通过 {@link InterviewSkillService#getSkill(String)} 验证</li>
     *   <li><b>创建实体</b> — 从请求参数填充 {@link VoiceInterviewSessionEntity}，
     *       初始状态设为 IN_PROGRESS，记录开始时间</li>
     *   <li><b>生成开场白</b> — 调用 LLM 根据技能方向、难度、开启环节生成 AI 面试官开场白</li>
     *   <li><b>保存首条消息</b> — 将开场白作为第一条 ai_text 消息持久化</li>
     *   <li><b>构造 WebSocket URL</b> — 使用服务器端口拼接 {@code ws://host:port/ws/voice-interview/{id}}</li>
     *   <li><b>返回响应</b> — 组装 {@link VoiceSessionResponse} 返回给前端</li>
     * </ol>
     * <p>
     * 异常情况：
     * <ul>
     *   <li>skillId 无效 → {@link ErrorCode#BAD_REQUEST}</li>
     *   <li>AI 开场白生成失败 → 使用默认开场白兜底，不阻断流程</li>
     * </ul>
     *
     * @param request 创建会话请求（skillId 必填）
     * @return 会话详情（含 WebSocket 连接地址和 AI 开场白）
     */
    @Transactional(rollbackFor = Exception.class)
    public VoiceSessionResponse createSession(CreateVoiceSessionRequest request, String wsUrlPrefix) {
        // ──────────────────────────────────────────────────────────────
        // Step 1: 校验 skillId — 通过 InterviewSkillService 验证存在性
        // ──────────────────────────────────────────────────────────────
        String skillId = request.skillId();
        String skillName;
        try {
            skillName = skillService.getSkill(skillId).name();
        } catch (BusinessException e) {
            // skillId 不存在时，使用 skillId 本身作为名称兜底
            log.warn("skillId '{}' 不存在于预设列表，将使用原始 ID 作为名称", skillId);
            skillName = skillId;
        }

        // ──────────────────────────────────────────────────────────────
        // Step 2: 创建 VoiceInterviewSessionEntity 并填充字段
        // ──────────────────────────────────────────────────────────────
        VoiceInterviewSessionEntity entity = new VoiceInterviewSessionEntity();

        // 必填字段
        entity.setSkillId(skillId);

        // 可选字段 — 使用 request 的值或保持 Entity 默认值
        if (request.difficulty() != null) entity.setDifficulty(request.difficulty());
        if (request.roleType() != null) entity.setRoleType(request.roleType());
        if (request.customJdText() != null) entity.setCustomJdText(request.customJdText());
        if (request.resumeId() != null) entity.setResumeId(request.resumeId());
        if (request.llmProvider() != null) entity.setLlmProvider(request.llmProvider());

        // 环节开关 — 优先取请求值，否则保持 Entity 默认值
        if (request.introEnabled() != null) entity.setIntroEnabled(request.introEnabled());
        if (request.techEnabled() != null) entity.setTechEnabled(request.techEnabled());
        if (request.projectEnabled() != null) entity.setProjectEnabled(request.projectEnabled());
        if (request.hrEnabled() != null) entity.setHrEnabled(request.hrEnabled());

        // 计划时长
        if (request.plannedDuration() != null) entity.setPlannedDuration(request.plannedDuration());

        // 状态: CREATED → IN_PROGRESS（创建即开始）
        entity.setStatus(VoiceSessionStatus.IN_PROGRESS);
        entity.setCurrentPhase("INTRO"); // 从自我介绍阶段开始
        entity.setStartTime(LocalDateTime.now());
        entity.setMessageCount(0);

        // 持久化会话（此时获得自增 id）
        VoiceInterviewSessionEntity savedEntity = sessionRepository.save(entity);
        Long sessionId = savedEntity.getId();
        log.info("语音面试会话已创建: sessionId={}, skillId={}, difficulty={}",
                sessionId, skillId, savedEntity.getDifficulty());

        // ──────────────────────────────────────────────────────────────
        // Step 3: 生成 AI 开场白（调用 LLM）
        // ──────────────────────────────────────────────────────────────
        String openingText = generateOpeningMessage(skillName, savedEntity);
        log.info("AI 开场白已生成: sessionId={}, length={}", sessionId, openingText.length());

        // ──────────────────────────────────────────────────────────────
        // Step 4: 保存首条 AI 消息（type=ai_text, phase=INTRO, seq=0）
        // ──────────────────────────────────────────────────────────────
        VoiceInterviewMessageEntity openingMessage = new VoiceInterviewMessageEntity();
        openingMessage.setSessionId(sessionId);
        openingMessage.setMessageType("ai_text");
        openingMessage.setPhase("INTRO");
        openingMessage.setAiGeneratedText(openingText);
        openingMessage.setTimestamp(LocalDateTime.now());
        openingMessage.setSequenceNum(0); // 首条消息序号为 0

        messageRepository.save(openingMessage);

        // 更新会话的消息计数
        savedEntity.setMessageCount(1);
        sessionRepository.save(savedEntity);
        log.debug("AI 开场白已持久化: sessionId={}, seq=0", sessionId);

        // ──────────────────────────────────────────────────────────────
        // Step 5: 构造 WebSocket URL — 根据前缀拼接完整地址
        //   前缀由 Controller 从 HttpServletRequest 动态推断：
        //     http  → ws://host:port/ws/voice-interview
        //     https → wss://host:port/ws/voice-interview
        // ──────────────────────────────────────────────────────────────
        String webSocketUrl = buildWebSocketUrl(wsUrlPrefix, sessionId);
        log.debug("WebSocket URL: {}", webSocketUrl);

        // ──────────────────────────────────────────────────────────────
        // Step 6: 组装 VoiceSessionResponse 返回
        // ──────────────────────────────────────────────────────────────
        return buildSessionResponse(savedEntity, webSocketUrl);
    }

    /**
     * 调用 AI 生成面试官开场白。
     * <p>
     * 使用 {@link #OPENING_SYSTEM_PROMPT} 作为系统提示，填充技能名称、难度和开启环节信息。
     * 如果 AI 调用失败（网络超时、格式异常等），使用预设的默认开场白兜底，
     * 确保会话创建流程不因 AI 异常而中断。
     * <p>
     * 兜底策略：因为语音面试的核心是 WebSocket 实时对话，开场白仅起引导作用，
     * AI 不可用时用默认文案不影响后续面试流程。
     *
     * @param skillName 技能显示名称（如 "Java 后端"）
     * @param entity    已持久化的会话实体（用于读取配置）
     * @return 开场白文本
     */
    private String generateOpeningMessage(String skillName, VoiceInterviewSessionEntity entity) {
        // 构建开启环节的中文描述
        String enabledPhases = buildEnabledPhasesDescription(entity);

        try {
            ChatClient chatClient = chatClientBuilder.build();

            // 填充提示词模板
            Prompt prompt = new PromptTemplate("""
                    {systemPrompt}
                    
                    面试方向：{skillName}
                    难度级别：{difficulty}
                    开启环节：{enabledPhases}
                    """).create(Map.of(
                    "systemPrompt", OPENING_SYSTEM_PROMPT,
                    "skillName", skillName,
                    "difficulty", getDifficultyLabel(entity.getDifficulty()),
                    "enabledPhases", enabledPhases
            ));

            String response = chatClient.prompt(prompt).call().content();

            if (response != null && !response.isBlank()) {
                return response.strip();
            }

            log.warn("AI 返回空开场白，使用默认文案");
        } catch (Exception e) {
            // AI 调用失败不阻断流程，使用默认文案兜底
            log.warn("AI 开场白生成失败（使用默认文案）: {}", e.getMessage());
        }

        // 兜底：默认开场白
        return String.format(
                "你好，欢迎参加今天的%s模拟面试。我是你的面试官，今天我们主要考察%s方向。%s" +
                "准备好了的话，我们就开始吧。",
                getDifficultyLabel(entity.getDifficulty()),
                skillName,
                entity.getTechEnabled() != null && entity.getTechEnabled() ? "先来聊聊技术基础。" : ""
        );
    }

    /**
     * 构建开启环节的中文描述（用于 LLM 提示词）。
     * <p>
     * 示例输出：先进行自我介绍，然后进入技术问答环节，最后深挖项目经验
     */
    private String buildEnabledPhasesDescription(VoiceInterviewSessionEntity entity) {
        StringBuilder sb = new StringBuilder();
        boolean intro = entity.getIntroEnabled() != null && entity.getIntroEnabled();
        boolean tech = entity.getTechEnabled() == null || entity.getTechEnabled(); // 默认 true
        boolean project = entity.getProjectEnabled() == null || entity.getProjectEnabled(); // 默认 true
        boolean hr = entity.getHrEnabled() != null && entity.getHrEnabled();

        if (intro) sb.append("自我介绍、");
        if (tech) sb.append("技术问答、");
        if (project) sb.append("项目深挖、");
        if (hr) sb.append("HR 综合问题、");

        // 删除末尾的顿号和逗号
        String result = sb.toString();
        if (result.endsWith("、")) {
            result = result.substring(0, result.length() - 1);
        }
        return result.isEmpty() ? "技术问答" : result;
    }

    /**
     * 将难度标识符转为中文标签。
     */
    private String getDifficultyLabel(String difficulty) {
        if (difficulty == null) return "中级";
        return switch (difficulty.toLowerCase()) {
            case "junior" -> "初级";
            case "mid" -> "中级";
            case "senior" -> "高级";
            default -> difficulty;
        };
    }

    /**
     * 构造完整的 WebSocket 连接 URL。
     * <p>
     * 格式：{wsUrlPrefix}/{sessionId}
     * <p>
     * wsUrlPrefix 由 Controller 从当前 HTTP 请求动态推断，保证：
     * <ul>
     *   <li>开发环境（http://localhost:8080）→ ws://localhost:8080/ws/voice-interview</li>
     *   <li>生产环境（https://example.com）→ wss://example.com/ws/voice-interview</li>
     *   <li>Docker/NAT 环境 → 自动适配真实 host 和 port</li>
     * </ul>
     *
     * @param wsUrlPrefix WebSocket URL 前缀（不含 sessionId）
     * @param sessionId   会话 ID
     * @return 完整的 WebSocket 连接地址
     */
    private String buildWebSocketUrl(String wsUrlPrefix, Long sessionId) {
        return String.format("%s/%d", wsUrlPrefix, sessionId);
    }

    /**
     * 将实体转为会话响应 DTO。
     * <p>
     * 将 {@link VoiceInterviewSessionEntity} 的各字段映射到
     * {@link VoiceSessionResponse}，时间字段格式化为 ISO-8601 字符串。
     *
     * @param entity       会话实体
     * @param webSocketUrl WebSocket 连接地址
     * @return 会话响应 DTO
     */
    private VoiceSessionResponse buildSessionResponse(
            VoiceInterviewSessionEntity entity, String webSocketUrl) {
        return new VoiceSessionResponse(
                entity.getId(),
                entity.getRoleType(),
                entity.getCurrentPhase(),
                entity.getStatus().name(),
                entity.getStartTime() != null
                        ? entity.getStartTime().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
                        : null,
                entity.getPlannedDuration(),
                webSocketUrl
        );
    }

    // ==================================================================
    // 会话 CRUD
    // ==================================================================

    /**
     * 获取单个会话详情。
     * <p>
     * 查询并返回会话的基本信息。webSocketUrl 为 null（前端只在创建/恢复时使用 URL）。
     *
     * @param sessionId 会话 ID
     * @return 会话详情
     * @throws BusinessException 如果会话不存在（VOICE_SESSION_NOT_FOUND）
     */
    public VoiceSessionResponse getSession(Long sessionId) {
        VoiceInterviewSessionEntity entity = findSessionOrThrow(sessionId);
        log.info("获取语音面试会话: sessionId={}, status={}", sessionId, entity.getStatus());
        return buildSessionResponse(entity, null);
    }

    /**
     * 获取会话列表。
     * <p>
     * status 为 null 时返回所有会话；非 null 时按状态筛选（如 "IN_PROGRESS"、"PAUSED"）。
     * userId 当前为预留字段。
     *
     * @param userId 用户 ID（预留，当前未使用）
     * @param status 会话状态筛选（可选，如 "COMPLETED"），大小写不敏感
     * @return 会话元数据列表
     */
    public List<VoiceSessionMeta> listSessions(String userId, String status) {
        List<VoiceInterviewSessionEntity> entities;

        if (status != null && !status.isBlank()) {
            // 按状态筛选
            VoiceSessionStatus sessionStatus = VoiceSessionStatus.valueOf(status.toUpperCase());
            entities = sessionRepository.findByStatusOrderByCreatedAtDesc(sessionStatus);
            log.info("获取语音面试会话列表: status={}, 共{}条", status, entities.size());
        } else {
            // 查全部
            entities = sessionRepository.findAllByOrderByCreatedAtDesc();
            log.info("获取所有语音面试会话: 共{}条", entities.size());
        }

        return entities.stream().map(this::entityToMeta).toList();
    }

    /**
     * 结束语音面试会话。
     * <p>
     * 仅非终态（CREATED / IN_PROGRESS / PAUSED）的会话可以结束。
     * endTime 设为当前时间，actualDuration 取整秒。
     *
     * @param sessionId 会话 ID
     * @throws BusinessException 会话不存在或已是终态
     */
    @Transactional
    public void endSession(Long sessionId) {
        VoiceInterviewSessionEntity entity = findSessionOrThrow(sessionId);

        // 校验：终态不可重复结束
        if (entity.getStatus() == VoiceSessionStatus.COMPLETED
                || entity.getStatus() == VoiceSessionStatus.EVALUATED) {
            throw new BusinessException(ErrorCode.VOICE_SESSION_CLOSED, "会话已结束，无需重复操作");
        }

        LocalDateTime now = LocalDateTime.now();
        entity.setStatus(VoiceSessionStatus.COMPLETED);
        entity.setEndTime(now);

        // 计算实际面试时长（秒）= 总耗时 - 累计暂停时长
        if (entity.getStartTime() != null) {
            long totalSeconds = java.time.Duration.between(entity.getStartTime(), now).getSeconds();
            int paused = entity.getTotalPausedSeconds() != null ? entity.getTotalPausedSeconds() : 0;
            entity.setActualDuration((int) Math.max(0, totalSeconds - paused));
            log.debug("时长计算: sessionId={}, 总耗时={}s, 暂停={}s, 实际={}s",
                    sessionId, totalSeconds, paused, entity.getActualDuration());
        }

        sessionRepository.save(entity);
        log.info("语音面试已结束: sessionId={}, duration={}s", sessionId, entity.getActualDuration());
    }

    /**
     * 删除语音面试会话及其所有消息。
     *
     * @param sessionId 会话 ID
     */
    @Transactional
    public void deleteSession(Long sessionId) {
        // 先删消息，再删会话
        messageRepository.deleteBySessionId(sessionId);
        sessionRepository.deleteById(sessionId);
        log.info("语音面试会话已删除: sessionId={}", sessionId);
    }

    // ==================================================================
    // 暂停/恢复
    // ==================================================================

    /**
     * 暂停语音面试会话。
     * <p>
     * 仅 IN_PROGRESS 状态的会话可以暂停。记录暂停时间，供 resume 时计算本次暂停时长。
     *
     * @param sessionId 会话 ID
     * @param request   暂停信息（含 reason，可为 null）
     * @throws BusinessException 会话不存在或不在 IN_PROGRESS 状态
     */
    @Transactional
    public void pauseSession(Long sessionId, PauseSessionRequest request) {
        VoiceInterviewSessionEntity entity = findSessionOrThrow(sessionId);

        if (entity.getStatus() != VoiceSessionStatus.IN_PROGRESS) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                    "只有进行中的会话才能暂停，当前状态: " + entity.getStatus());
        }

        entity.setStatus(VoiceSessionStatus.PAUSED);
        entity.setPausedAt(LocalDateTime.now());
        entity.setPauseReason(request != null ? request.reason() : "user_initiated");

        sessionRepository.save(entity);
        log.info("语音面试已暂停: sessionId={}, reason={}", sessionId, entity.getPauseReason());
    }

    /**
     * 恢复已暂停的语音面试会话。
     * <p>
     * 仅 PAUSED 状态的会话可以恢复。恢复时计算本次暂停的持续秒数，
     * 累加到 {@code totalPausedSeconds} 中，供 end 时计算实际面试时长。
     * 清空暂停标记并构造新的 WebSocket 地址。
     *
     * @param sessionId   会话 ID
     * @param wsUrlPrefix WebSocket URL 前缀（由 Controller 从 HttpServletRequest 推断）
     * @return 更新后的会话详情（含新的 WebSocket 地址）
     * @throws BusinessException 会话不存在或不在 PAUSED 状态
     */
    @Transactional
    public VoiceSessionResponse resumeSession(Long sessionId, String wsUrlPrefix) {
        VoiceInterviewSessionEntity entity = findSessionOrThrow(sessionId);

        if (entity.getStatus() != VoiceSessionStatus.PAUSED) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                    "只有已暂停的会话才能恢复，当前状态: " + entity.getStatus());
        }

        // 累加本次暂停时长（秒）
        if (entity.getPausedAt() != null) {
            long pausedSeconds = java.time.Duration.between(entity.getPausedAt(), LocalDateTime.now()).getSeconds();
            int total = (entity.getTotalPausedSeconds() != null ? entity.getTotalPausedSeconds() : 0)
                    + (int) Math.max(0, pausedSeconds);
            entity.setTotalPausedSeconds(total);
            log.debug("累加暂停时长: sessionId={}, 本次={}s, 累计={}s", sessionId, pausedSeconds, total);
        }

        entity.setStatus(VoiceSessionStatus.IN_PROGRESS);
        entity.setPausedAt(null);
        entity.setPauseReason(null);

        sessionRepository.save(entity);

        String webSocketUrl = buildWebSocketUrl(wsUrlPrefix, sessionId);
        log.info("语音面试已恢复: sessionId={}", sessionId);

        return buildSessionResponse(entity, webSocketUrl);
    }

    // ==================================================================
    // 消息 & 评估
    // ==================================================================

    /**
     * 获取会话的消息历史。
     *
     * @param sessionId 会话 ID
     * @return 消息列表（按 sequenceNum 升序）
     * @throws BusinessException 会话不存在时抛出
     */
    public List<VoiceInterviewMessageDTO> getMessages(Long sessionId) {
        // 校验会话存在
        findSessionOrThrow(sessionId);

        List<VoiceInterviewMessageEntity> entities =
                messageRepository.findBySessionIdOrderBySequenceNumAsc(sessionId);

        log.info("获取语音面试消息: sessionId={}, 共{}条", sessionId, entities.size());
        return entities.stream().map(this::entityToMessageDTO).toList();
    }

    /**
     * 获取面试评估状态（前端轮询用）。
     * <p>
     * COMPLETED 时反序列化 evaluationJson 为 {@link VoiceEvaluationDetail} 一并返回。
     *
     * @param sessionId 会话 ID
     * @return 评估状态
     * @throws BusinessException 会话不存在时抛出
     */
    public VoiceEvaluationStatusResponse getEvaluation(Long sessionId) {
        VoiceInterviewSessionEntity entity = findSessionOrThrow(sessionId);

        VoiceEvaluationDetail detail = null;
        if (entity.getEvaluateStatus() == AsyncTaskStatus.COMPLETED
                && entity.getEvaluationJson() != null
                && !entity.getEvaluationJson().isBlank()) {
            try {
                detail = objectMapper.readValue(entity.getEvaluationJson(), VoiceEvaluationDetail.class);
            } catch (Exception e) {
                log.warn("反序列化 evaluationJson 失败: sessionId={}, error={}", sessionId, e.getMessage());
            }
        }

        String status = entity.getEvaluateStatus() != null ? entity.getEvaluateStatus().name() : null;
        log.info("获取语音面试评估状态: sessionId={}, status={}", sessionId, status);

        return new VoiceEvaluationStatusResponse(status, entity.getEvaluateError(), detail);
    }

    /**
     * 触发异步面试评估——通过 Redis Stream 异步执行。
     * <p>
     * 核心流程：
     * <ol>
     *   <li>校验会话存在</li>
     *   <li>幂等保护：如果已经是 PROCESSING，直接返回当前状态（不重复触发）</li>
     *   <li>设置 evaluateStatus = PENDING，持久化</li>
     *   <li>通过 {@link VoiceEvaluationStreamProducer} 发送消息到 Redis Stream</li>
     *   <li>Stream 消费者在后台拾取消息，异步执行评估</li>
     *   <li>返回当前状态（前端通过 GET /evaluation 轮询直到 COMPLETED）</li>
     * </ol>
     * <p>
     * 异步链路：
     * <pre>
     * 本方法 → Redis Stream (voice:evaluation:stream)
     *   → VoiceEvaluationStreamConsumer （后台守护线程）
     *     → VoiceEvaluationService.evaluate(sessionId)
     *       → 写回 evaluationJson + evaluateStatus = COMPLETED
     * </pre>
     *
     * @param sessionId 会话 ID
     * @return 当前评估状态（PENDING）
     * @throws BusinessException 会话不存在时抛出
     */
    @Transactional
    public VoiceEvaluationStatusResponse triggerEvaluation(Long sessionId) {
        VoiceInterviewSessionEntity entity = findSessionOrThrow(sessionId);

        // 已经在处理中 → 幂等，不重复触发
        if (entity.getEvaluateStatus() == AsyncTaskStatus.PROCESSING) {
            log.info("评估已在处理中，跳过: sessionId={}", sessionId);
            return getEvaluation(sessionId);
        }

        // 已经是 COMPLETED → 直接返回结果
        if (entity.getEvaluateStatus() == AsyncTaskStatus.COMPLETED) {
            log.info("评估已完成，直接返回: sessionId={}", sessionId);
            return getEvaluation(sessionId);
        }

        // 设为 PENDING
        entity.setEvaluateStatus(AsyncTaskStatus.PENDING);
        entity.setEvaluateError(null);
        sessionRepository.save(entity);

        // 发送消息到 Redis Stream（生产者内部已确保消费者组存在）
        evaluationStreamProducer.publish(sessionId);

        log.info("语音面试评估已触发（Redis Stream）: sessionId={}", sessionId);
        return getEvaluation(sessionId);
    }

    // ==================================================================
    // 内部工具方法
    // ==================================================================

    /**
     * 查找会话，不存在时抛异常。
     */
    private VoiceInterviewSessionEntity findSessionOrThrow(Long sessionId) {
        return sessionRepository.findById(sessionId)
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.VOICE_SESSION_NOT_FOUND, "语音面试会话不存在: " + sessionId));
    }

    /**
     * Entity → VoiceSessionMeta 转换。
     */
    private VoiceSessionMeta entityToMeta(VoiceInterviewSessionEntity entity) {
        return new VoiceSessionMeta(
                entity.getId(),
                entity.getRoleType(),
                entity.getStatus().name(),
                entity.getCurrentPhase(),
                entity.getCreatedAt() != null
                        ? entity.getCreatedAt().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME) : null,
                entity.getUpdatedAt() != null
                        ? entity.getUpdatedAt().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME) : null,
                entity.getActualDuration(),
                entity.getMessageCount(),
                entity.getEvaluateStatus() != null ? entity.getEvaluateStatus().name() : null,
                entity.getEvaluateError()
        );
    }

    /**
     * Entity → VoiceInterviewMessageDTO 转换。
     */
    private VoiceInterviewMessageDTO entityToMessageDTO(VoiceInterviewMessageEntity entity) {
        return new VoiceInterviewMessageDTO(
                entity.getId(),
                entity.getSessionId(),
                entity.getMessageType(),
                entity.getPhase(),
                entity.getUserRecognizedText(),
                entity.getAiGeneratedText(),
                entity.getTimestamp() != null
                        ? entity.getTimestamp().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME) : null,
                entity.getSequenceNum()
        );
    }
}
