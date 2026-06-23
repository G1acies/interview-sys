package interview.guide.modules.interview.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.infrastructure.export.PdfExportService;
import interview.guide.infrastructure.redis.InterviewSessionCache;
import interview.guide.infrastructure.redis.InterviewSessionCache.CachedSession;
import interview.guide.modules.interview.model.*;
import interview.guide.modules.interview.model.InterviewSessionDTO.SessionStatus;
import interview.guide.modules.interview.repository.InterviewAnswerRepository;
import interview.guide.modules.interview.repository.InterviewSessionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 面试核心业务服务。
 * <p>
 * 负责面试会话的全生命周期管理，包括创建会话、AI 出题、列表查询等。
 * AI 出题流程：
 * <pre>
 * 客户端请求 → ChatClient 调用 LLM → 解析 JSON 响应 → InterviewQuestionDTO[]
 *   → InterviewSessionEntity 持久化 → InterviewSessionDTO 返回
 * </pre>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InterviewService {

    private final InterviewSessionRepository sessionRepository;
    private final InterviewAnswerRepository answerRepository;
    private final InterviewSessionCache sessionCache;
    private final AnswerEvaluationService answerEvaluationService;
    private final PdfExportService pdfExportService;
    private final ChatClient.Builder chatClientBuilder;
    private final ObjectMapper objectMapper;

    /**
     * AI 系统提示词 — 要求 LLM 以固定 JSON 格式返回面试题。
     * <p>
     * 设计要点：
     * <ul>
     *   <li>明确要求返回 JSON 数组，不包含 markdown 代码块，方便直接解析</li>
     *   <li>每个字段给出类型和示例，减少 AI 格式幻觉</li>
     *   <li>要求题目紧扣简历内容，且与历史问题不重复</li>
     * </ul>
     */
    private static final String SYSTEM_PROMPT = """
            你是一个资深的面试官。请根据简历内容生成面试题。

            要求：
            - 必须返回 JSON 数组，格式如下（不要包含 ```json 等 markdown 标记）：
            [
              {
                "question": "题面",
                "type": "JAVA",
                "category": "Java 集合",
                "topicSummary": "HashMap 原理"
              }
            ]
            - type 是面试方向分类，如 JAVA / SPRING / MYSQL / PROJECT 等
            - category 是更细粒度的知识点分类
            - 题目要紧扣简历中的项目经验和技术栈
            - 每道题考察一个独立的知识点，不要概念重复
            - 题量严格等于 {questionCount} 道
            - 难度参考 {difficulty} 级别
            
            只输出 JSON，不需要任何额外说明文字。
            """;

    /**
     * 获取全部面试会话列表（按创建时间倒序）。
     */
    public List<SessionListItemDTO> listSessions() {
        return sessionRepository.findAllByOrderByCreatedAtDesc()
                .stream()
                .map(SessionListItemDTO::from)
                .toList();
    }

    /**
     * 创建面试会话 — 核心流程：
     * <ol>
     *   <li>校验请求参数（简历文本必填）</li>
     *   <li>生成唯一 sessionId（UUID 前 16 位）</li>
     *   <li>调用 AI 根据简历生成面试题</li>
     *   <li>解析 AI 返回的 JSON 为 {@link InterviewQuestionDTO} 列表</li>
     *   <li>创建 {@link InterviewSessionEntity} 并持久化</li>
     *   <li>返回 {@link InterviewSessionDTO} 给前端</li>
     * </ol>
     *
     * @param request 创建请求（含简历文本、题目数量等）
     * @return 创建完成的面试会话 DTO
     */
    @Transactional(rollbackFor = Exception.class)
    public InterviewSessionDTO createSession(CreateInterviewRequest request) {
        // 1. 校验简历文本
        String resumeText = request.resumeText();
        if (resumeText == null || resumeText.isBlank()) {
            throw new BusinessException(ErrorCode.RESUME_NOT_FOUND, "简历文本不能为空");
        }

        // 2. 生成会话 ID
        String sessionId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        String skillId = request.skillId() != null ? request.skillId() : "java-backend";
        String difficulty = request.difficulty() != null ? request.difficulty() : "mid";
        int questionCount = request.questionCount();

        log.info("创建面试会话: sessionId={}, skill={}, difficulty={}, questionCount={}",
                sessionId, skillId, difficulty, questionCount);

        // 3. AI 生成面试题
        List<InterviewQuestionDTO> questions = generateQuestionsByAI(
                resumeText, questionCount, skillId, difficulty);

        if (questions == null || questions.isEmpty()) {
            log.error("AI 生成题目为空: sessionId={}", sessionId);
            throw new BusinessException(ErrorCode.AI_SERVICE_ERROR, "AI 生成题目失败，请重试");
        }

        // 4. 创建并持久化实体
        InterviewSessionEntity entity = new InterviewSessionEntity();
        entity.setSessionId(sessionId);
        entity.setSkillId(skillId);
        entity.setDifficulty(difficulty);
        entity.setTotalQuestions(questions.size());
        entity.setCurrentQuestionIndex(0);
        entity.setStatus(InterviewSessionEntity.SessionStatus.CREATED);
        try {
            entity.setQuestionsJson(objectMapper.writeValueAsString(questions));    //包含了json序列化
        } catch (Exception e) {
            log.error("序列化题目列表失败: {}", e.getMessage(), e);
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "保存题目失败");
        }

        // 写入db
        InterviewSessionEntity saved = sessionRepository.save(entity);
        log.info("面试会话已持久化: sessionId={}, id={}", sessionId, saved.getId());

        // 5. 写入 Redis 缓存（会话状态用 DTO 的 SessionStatus）
        try {
            sessionCache.saveSession(
                    sessionId,
                    resumeText,
                    request.resumeId(),
                    questions,
                    0,
                    SessionStatus.CREATED
            );
            log.debug("面试会话已缓存到 Redis: sessionId={}", sessionId);
        } catch (Exception e) {
            // Redis 不可用不应阻断主流程，仅记录警告
            log.warn("Redis 缓存会话失败（不影响主流程）: sessionId={}, error={}",
                    sessionId, e.getMessage());
        }

        // 6. 返回 DTO（questions 中包含完整题目列表）
        return new InterviewSessionDTO(
                sessionId,
                resumeText,
                questions.size(),
                0,
                questions,
                InterviewSessionDTO.SessionStatus.CREATED
        );
    }

    /**
     * 获取面试会话信息。
     * <p>
     * 查询优先级：
     * <ol>
     *   <li><b>Redis 缓存</b> — 面试进行中的会话缓存在 Redis 中，读取最快</li>
     *   <li><b>数据库恢复</b> — 缓存未命中时从 MySQL 加载并回填缓存</li>
     * </ol>
     *
     * @param sessionId 会话唯一标识
     * @return 面试会话 DTO（含完整题目列表）
     * @throws BusinessException 当会话不存在时抛出 INTERVIEW_SESSION_NOT_FOUND
     */
    public InterviewSessionDTO getSession(String sessionId) {
        // 1. 优先从 Redis 缓存获取
        Optional<CachedSession> cachedOpt = sessionCache.getSession(sessionId);
        if (cachedOpt.isPresent()) {
            log.debug("从 Redis 缓存获取会话: sessionId={}", sessionId);
            return toDTO(cachedOpt.get());
        }

        // 2. 缓存未命中，从数据库恢复（含答案填充 + 回填缓存）
        CachedSession restored = restoreSessionFromDatabase(sessionId);
        if (restored == null) {
            throw new BusinessException(ErrorCode.INTERVIEW_SESSION_NOT_FOUND,
                    "面试会话不存在: " + sessionId);
        }

        return toDTO(restored);
    }

    /**
     * 获取当前题目。
     * <p>
     * 根据会话的 {@code currentQuestionIndex} 定位到当前应答题的题目。
     * 如果所有题目已完成（索引越界），返回 {@code completed=true} 状态。
     *
     * @param sessionId 会话唯一标识
     * @return 当前题目响应（含完成状态和题目内容）
     * @throws BusinessException 当会话不存在时抛出 INTERVIEW_SESSION_NOT_FOUND
     */
    public CurrentQuestionResponse getCurrentQuestion(String sessionId) {
        InterviewSessionDTO session = getSession(sessionId);

        // 判断是否已答完所有题
        if (session.currentQuestionIndex() >= session.questions().size()) {
            return new CurrentQuestionResponse(
                    true,   // completed
                    null,   // question
                    "所有问题已回答完毕"
            );
        }

        // 返回当前题目（未答完时 question 不为 null）
        InterviewQuestionDTO question = session.questions()
                .get(session.currentQuestionIndex());
        return new CurrentQuestionResponse(
                false,   // completed
                question,
                null     // message
        );
    }

    // ==================== 答题流程 ====================

    /**
     * 提交答案并进入下一题。
     * <p>
     * 处理流程：
     * <ol>
     *   <li>获取当前会话（优先 Redis 缓存）</li>
     *   <li>校验 questionIndex 合法性</li>
     *   <li>更新问题对象的 {@code userAnswer}</li>
     *   <li>持久化答案到 MySQL（{@link InterviewAnswerEntity}）</li>
     *   <li>更新 Redis 缓存中的问题列表和当前索引</li>
     *   <li>返回下一题信息（或告知已完成）</li>
     * </ol>
     *
     * @param sessionId     会话唯一标识
     * @param questionIndex 当前回答的问题索引
     * @param answer        用户作答内容
     * @return 提交答案响应（含下一题信息或完成状态）
     * @throws BusinessException 会话不存在 / 题目索引无效
     */
    @Transactional(rollbackFor = Exception.class)
    public SubmitAnswerResponse submitAnswer(String sessionId, Integer questionIndex, String answer) {
        // 1. 获取会话
        InterviewSessionDTO session = getSession(sessionId);

        // 2. 校验索引
        List<InterviewQuestionDTO> questions = session.questions();
        if (questionIndex == null || questionIndex < 0 || questionIndex >= questions.size()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                    "无效的问题索引: " + questionIndex);
        }

        // 3. 更新问题答案
        InterviewQuestionDTO updated = questions.get(questionIndex).withAnswer(answer);
        questions.set(questionIndex, updated);

        log.info("提交答案: sessionId={}, questionIndex={}, 答案长度={}",
                sessionId, questionIndex, answer != null ? answer.length() : 0);

        // 4. 持久化到 MySQL（upsert：有则更新，无则新建）
        sessionRepository.findBySessionId(sessionId).ifPresent(entity -> {
            InterviewAnswerEntity answerEntity = answerRepository
                    .findBySession_SessionIdAndQuestionIndex(sessionId, questionIndex)
                    .orElseGet(() -> {
                        InterviewAnswerEntity created = new InterviewAnswerEntity();
                        created.setSession(entity);
                        created.setQuestionIndex(questionIndex);
                        return created;
                    });

            answerEntity.setQuestion(updated.question());
            answerEntity.setCategory(updated.category());
            answerEntity.setUserAnswer(answer);

            answerRepository.save(answerEntity);
            log.debug("答案已持久化: sessionId={}, questionIndex={}", sessionId, questionIndex);

            // 将带答案的题目列表写回 Entity.questionsJson
            // 这样 evaluateAndSaveReport() 反序列化时才能拿到 userAnswer
            try {
                entity.setQuestionsJson(objectMapper.writeValueAsString(questions));
                sessionRepository.save(entity);
            } catch (Exception e) {
                log.warn("更新 questionsJson 失败（不影响主流程）: {}", e.getMessage());
            }
        });

        // 5. 推进到下一题
        int nextIndex = questionIndex + 1;
        boolean hasNext = nextIndex < questions.size();

        if (hasNext) {
            // 更新 Redis 缓存
            sessionCache.updateQuestions(sessionId, questions);
            sessionCache.updateCurrentIndex(sessionId, nextIndex);
            log.debug("已推进到下一题: sessionId={}, currentIndex={}, 还有{}题",
                    sessionId, nextIndex, questions.size() - nextIndex);
        } else {
            // 已答完所有题
            sessionCache.updateQuestions(sessionId, questions);
            sessionCache.updateSessionStatus(sessionId, SessionStatus.COMPLETED);

            // 更新 DB 状态
            sessionRepository.findBySessionId(sessionId).ifPresent(entity -> {
                entity.setCurrentQuestionIndex(nextIndex);
                entity.setStatus(InterviewSessionEntity.SessionStatus.COMPLETED);
                sessionRepository.save(entity);
            });

            log.info("所有题目已回答完毕: sessionId={}", sessionId);
        }

        // 6. 返回响应
        InterviewQuestionDTO nextQuestion = hasNext ? questions.get(nextIndex) : null;
        return new SubmitAnswerResponse(
                hasNext,
                nextQuestion,
                questionIndex,
                questions.size()
        );
    }

    /**
     * 暂存答案（不推进到下一题）。
     * <p>
     * 与 {@link #submitAnswer} 的区别：
     * <ul>
     *   <li><b>POST</b> — 提交 + 推进 currentQuestionIndex，返回下一题</li>
     *   <li><b>PUT</b> — 仅保存，不推进，返回 {@code void}</li>
     * </ul>
     * 暂存用于前端防丢失草稿，用户可以在答题框中随时保存进度。
     *
     * @param sessionId     会话唯一标识
     * @param questionIndex 保存答案的问题索引
     * @param answer        答案内容
     * @throws BusinessException 会话不存在 / 题目索引无效
     */
    @Transactional(rollbackFor = Exception.class)
    public void saveAnswer(String sessionId, Integer questionIndex, String answer) {
        // 1. 获取会话
        InterviewSessionDTO session = getSession(sessionId);

        // 2. 校验索引
        List<InterviewQuestionDTO> questions = session.questions();
        if (questionIndex == null || questionIndex < 0 || questionIndex >= questions.size()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                    "无效的问题索引: " + questionIndex);
        }

        // 3. 更新问题答案
        InterviewQuestionDTO updated = questions.get(questionIndex).withAnswer(answer);
        questions.set(questionIndex, updated);

        log.info("暂存答案: sessionId={}, questionIndex={}, 答案长度={}",
                sessionId, questionIndex, answer != null ? answer.length() : 0);

        // 4. 持久化到 MySQL（upsert）
        sessionRepository.findBySessionId(sessionId).ifPresent(entity -> {
            InterviewAnswerEntity answerEntity = answerRepository
                    .findBySession_SessionIdAndQuestionIndex(sessionId, questionIndex)
                    .orElseGet(() -> {
                        InterviewAnswerEntity created = new InterviewAnswerEntity();
                        created.setSession(entity);
                        created.setQuestionIndex(questionIndex);
                        return created;
                    });

            answerEntity.setQuestion(updated.question());
            answerEntity.setCategory(updated.category());
            answerEntity.setUserAnswer(answer);

            answerRepository.save(answerEntity);
            log.debug("暂存答案已持久化: sessionId={}, questionIndex={}", sessionId, questionIndex);

            // 同步更新 Entity.questionsJson，确保答案不丢失
            try {
                entity.setQuestionsJson(objectMapper.writeValueAsString(questions));
                sessionRepository.save(entity);
            } catch (Exception e) {
                log.warn("更新 questionsJson 失败（不影响主流程）: {}", e.getMessage());
            }
        });

        // 5. 更新 Redis 缓存（只更新问题列表，不推进索引）
        sessionCache.updateQuestions(sessionId, questions);
        log.debug("暂存答案已更新 Redis 缓存: sessionId={}", sessionId);
    }

    /**
     * 提前交卷（结束面试）。
     * <p>
     * 无论当前答到第几题，将会话状态设为 COMPLETED。
     * 后续可通过 {@link #generateReport} 触发 AI 评估生成报告。
     * <p>
     * 执行步骤：
     * <ol>
     *   <li>校验状态必须是 CREATED 或 IN_PROGRESS</li>
     *   <li>更新状态 → COMPLETED，记录完成时间</li>
     *   <li>MySQL 持久化</li>
     *   <li>Redis 缓存同步</li>
     * </ol>
     *
     * @param sessionId 会话唯一标识
     * @throws BusinessException 会话不存在或已结束
     */
    @Transactional(rollbackFor = Exception.class)
    public void completeInterview(String sessionId) {
        InterviewSessionEntity entity = sessionRepository.findBySessionId(sessionId)
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.INTERVIEW_SESSION_NOT_FOUND, "会话不存在: " + sessionId));

        // 1. 校验状态
        if (entity.getStatus() == InterviewSessionEntity.SessionStatus.COMPLETED
                || entity.getStatus() == InterviewSessionEntity.SessionStatus.EVALUATED) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "面试已结束，无需重复交卷");
        }

        // 2. 更新状态
        entity.setStatus(InterviewSessionEntity.SessionStatus.COMPLETED);
        entity.setCompletedAt(java.time.LocalDateTime.now());
        // 已答完的不用改 currentQuestionIndex，提前交卷的跳到末尾
        if (entity.getCurrentQuestionIndex() < entity.getTotalQuestions()) {
            entity.setCurrentQuestionIndex(entity.getTotalQuestions());
        }

        // 3. MySQL 持久化
        sessionRepository.save(entity);
        log.info("面试已交卷: sessionId={}, status=COMPLETED", sessionId);

        // 4. Redis 缓存同步（非关键路径，失败不影响主流程）
        try {
            sessionCache.updateSessionStatus(sessionId, SessionStatus.COMPLETED);
        } catch (Exception e) {
            log.warn("更新 Redis 缓存失败（不影响主流程）: sessionId={}", sessionId);
        }
    }

    /**
     * 从数据库恢复会话并缓存到 Redis。
     * <p>
     * 流程：
     * <ol>
     *   <li>按 sessionId 查询 {@link InterviewSessionEntity}（JOIN FETCH 简历避免 N+1）</li>
     *   <li>反序列化 questionsJson → {@link List}{@literal <}{@link InterviewQuestionDTO}{@literal >}</li>
     *   <li>写入 Redis 缓存供后续快速访问</li>
     *   <li>返回 {@link CachedSession}</li>
     * </ol>
     *
     * @param sessionId 会话唯一标识
     * @return 缓存会话对象，数据库中也找不到时返回 null
     */
    private CachedSession restoreSessionFromDatabase(String sessionId) {
        try {
            Optional<InterviewSessionEntity> entityOpt =
                    sessionRepository.findBySessionIdWithResume(sessionId);
            if (entityOpt.isEmpty()) {
                return null;
            }

            InterviewSessionEntity entity = entityOpt.get();

            // 反序列化题目列表
            List<InterviewQuestionDTO> questions = objectMapper.readValue(
                    entity.getQuestionsJson(),
                    new TypeReference<List<InterviewQuestionDTO>>() {}
            );

            // 从关联的简历提取文本
            String resumeText = entity.getResume() != null
                    ? entity.getResume().getRawText()
                    : "";

            SessionStatus status = convertStatus(entity.getStatus());

            // 回填 Redis 缓存
            sessionCache.saveSession(
                    entity.getSessionId(),
                    resumeText,
                    entity.getResumeId(),
                    questions,
                    entity.getCurrentQuestionIndex() != null ? entity.getCurrentQuestionIndex() : 0,
                    status
            );

            log.info("已从数据库恢复会话到 Redis: sessionId={}, status={}",
                    sessionId, entity.getStatus());

            // 从缓存中取出刚存入的数据返回
            return sessionCache.getSession(sessionId).orElse(null);

        } catch (Exception e) {
            log.error("从数据库恢复会话失败: sessionId={}, error={}", sessionId, e.getMessage(), e);
            return null;
        }
    }

    /**
     * 将 Redis {@link CachedSession} 转换为前端需要的 {@link InterviewSessionDTO}。
     */
    private InterviewSessionDTO toDTO(CachedSession session) {
        List<InterviewQuestionDTO> questions = session.getQuestions(objectMapper);
        return new InterviewSessionDTO(
                session.getSessionId(),
                session.getResumeText(),
                questions.size(),
                session.getCurrentIndex(),
                questions,
                session.getStatus()
        );
    }

    /**
     * 将实体层的 {@link InterviewSessionEntity.SessionStatus} 转为 DTO 层的 {@link SessionStatus}。
     * <p>
     * 两个枚举的值虽然同名，但分属不同类，需要显式转换。
     */
    private SessionStatus convertStatus(InterviewSessionEntity.SessionStatus status) {
        return switch (status) {
            case CREATED -> SessionStatus.CREATED;
            case IN_PROGRESS -> SessionStatus.IN_PROGRESS;
            case COMPLETED -> SessionStatus.COMPLETED;
            case EVALUATED -> SessionStatus.EVALUATED;
        };
    }

    /**
     * 调用 AI 生成面试题目。
     * <p>
     * 流程：
     * <ol>
     *   <li>组装 Prompt（系统提示 + 简历内容 + 参数）</li>
     *   <li>调用 ChatClient 获取 LLM 响应</li>
     *   <li>去除可能存在的 markdown 代码块标记</li>
     *   <li>解析 JSON 数组 → {@link List}{@literal <}{@link InterviewQuestionDTO}{@literal >}</li>
     *   <li>重新编号确保 questionIndex 从 0 开始连续</li>
     * </ol>
     *
     * @param resumeText    简历文本
     * @param questionCount 期望的题目数量
     * @param skillId       面试方向
     * @param difficulty    难度级别
     * @return 题目列表（已编号）
     */
    /**
     * 重试时追加的严格 JSON 指令 — 强调字符转义和格式正确性。
     */
    private static final String RETRY_STRICT_JSON = """
        
        【重要】上次输出 JSON 格式有误，请严格遵守：
        1) 字符串内部的所有双引号必须转义为 \\"，不能使用未转义的 " 字符
        2) 不要输出 Markdown 代码块（```json）
        3) 不要包含任何额外说明文字，只输出 JSON 数组
        4) 确保 JSON 语法完全正确，可以用 JSON 验证器检查
        """;

    private List<InterviewQuestionDTO> generateQuestionsByAI(
            String resumeText, int questionCount, String skillId, String difficulty) {

        ChatClient chatClient = chatClientBuilder.build();
        String baseSystemPrompt = SYSTEM_PROMPT
                .replace("{questionCount}", String.valueOf(questionCount))
                .replace("{difficulty}", difficulty != null ? difficulty : "mid");
        int maxAttempts = 2;
        String lastError = null;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            // 第 2 次重试时追加严格 JSON 指令
            String systemPrompt = baseSystemPrompt;
            if (attempt > 1) {
                systemPrompt = baseSystemPrompt + RETRY_STRICT_JSON;
            }

            // 组装 Prompt
            Prompt prompt = new PromptTemplate("""
                    {systemPrompt}
                    
                    简历内容：
                    {resumeText}
                    """).create(Map.of(
                    "systemPrompt", systemPrompt,
                    "resumeText", resumeText
            ));

            String response = chatClient.prompt(prompt)
                    .call()
                    .content();

            log.debug("AI 原始响应 (第{}次): {}", attempt, response);

            if (response == null || response.isBlank()) {
                lastError = "AI 返回空响应";
                if (attempt < maxAttempts) {
                    log.warn("{}，将重试", lastError);
                    continue;
                }
                throw new BusinessException(ErrorCode.AI_SERVICE_ERROR, "AI 未返回任何内容");
            }

            // 提取纯 JSON
            String json = extractJson(response);

            try {
                // 解析 AI 返回的题目列表
                List<RawQuestion> rawQuestions = objectMapper.readValue(
                        json, new TypeReference<List<RawQuestion>>() {});

                if (rawQuestions == null || rawQuestions.isEmpty()) {
                    log.warn("AI 返回空题目列表");
                    return List.of();
                }

                // 重新编号并转换为 InterviewQuestionDTO
                List<InterviewQuestionDTO> result = new ArrayList<>();
                for (int i = 0; i < rawQuestions.size(); i++) {
                    RawQuestion rq = rawQuestions.get(i);
                    String type = rq.type != null ? rq.type.toUpperCase() : skillId.toUpperCase();
                    result.add(InterviewQuestionDTO.create(
                            i,
                            rq.question != null ? rq.question : "",
                            type,
                            rq.category != null ? rq.category : type,
                            rq.topicSummary,
                            false,
                            null
                    ));
                }

                log.info("AI 生成题目完成: 请求={} 道, 实际={} 道", questionCount, result.size());
                return result;

            } catch (Exception e) {
                lastError = e.getMessage() != null ? e.getMessage() : "未知错误";
                log.warn("解析 AI 响应 JSON 失败 (第{}次): {}", attempt, lastError);
                if (attempt >= maxAttempts) {
                    log.error("重试耗尽，json={}", json);
                }
                // attempt < maxAttempts 时继续循环，不 throw
            }
        }

        throw new BusinessException(ErrorCode.AI_STRUCTURED_OUTPUT_FAILED,
                "AI 返回格式异常，重试" + maxAttempts + "次后仍无法解析: " + lastError);
    }

    /**
     * 从 AI 的原始文本中提取纯 JSON。
     * <p>
     * AI 有时会在 JSON 外面包裹 markdown 代码块标记：
     * <pre>
     * ```json
     * [...]
     * ```
     * </pre>
     * 此方法去除这些包裹标记，只保留 JSON 正文。
     */
    private String extractJson(String raw) {
        String s = raw.strip();
        // 去掉开头的 ```json 或 ```
        s = s.replaceAll("^```(?:json)?\\s*\\n?", "");
        // 去掉结尾的 ```
        s = s.replaceAll("\\n?```$", "");
        return s.strip();
    }

    /**
     * 生成或获取面试评估报告。
     * <p>
     * 根据会话状态走不同分支：
     * <ul>
     *   <li><b>CREATED / IN_PROGRESS</b> — 面试尚未完成，抛异常</li>
     *   <li><b>EVALUATED</b> — 报告已存在，从 DB 反序列化后直接返回</li>
     *   <li><b>COMPLETED</b> — 触发 AI 评估，保存结果，返回报告</li>
     * </ul>
     *
     * @param sessionId 会话唯一标识
     * @return 面试评估报告
     */
    @Transactional(rollbackFor = Exception.class)
    public InterviewReportDTO generateReport(String sessionId) {
        // 使用 JOIN FETCH 加载简历，COMPLETED 分支需要简历文本用于 AI 评估
        InterviewSessionEntity entity = sessionRepository.findBySessionIdWithResume(sessionId)
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.INTERVIEW_SESSION_NOT_FOUND, "会话不存在: " + sessionId));

        // 1. CREATED / IN_PROGRESS → 面试还没答完
        if (entity.getStatus() == InterviewSessionEntity.SessionStatus.CREATED
                || entity.getStatus() == InterviewSessionEntity.SessionStatus.IN_PROGRESS) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "面试尚未完成，无法生成报告");
        }

        // 2. EVALUATED → 报告已存在，直接反序列化返回
        if (entity.getStatus() == InterviewSessionEntity.SessionStatus.EVALUATED) {
            log.info("报告已存在，从 DB 反序列化: sessionId={}", sessionId);
            return deserializeReport(entity);
        }

        // 3. COMPLETED → 触发 AI 评估，保存报告
        log.info("会话已完成，开始 AI 评估: sessionId={}", sessionId);
        return evaluateAndSaveReport(entity);
    }

    /**
     * 执行 AI 评估并保存报告。
     * <p>
     * COMPLETED 分支的核心逻辑：
     * <ol>
     *   <li>反序列化题目列表（含已保存的用户答案）</li>
     *   <li>提取简历文本</li>
     *   <li>调用 {@link AnswerEvaluationService#evaluateInterview} 执行 AI 评估</li>
     *   <li>将评估报告持久化到 Entity（JSON 字段）</li>
     *   <li>更新 Redis 缓存状态为 EVALUATED</li>
     *   <li>返回报告</li>
     * </ol>
     */
    private InterviewReportDTO evaluateAndSaveReport(InterviewSessionEntity entity) {
        String sessionId = entity.getSessionId();

        // 1. 反序列化题目列表（含 userAnswer）
        List<InterviewQuestionDTO> questions = parseJsonList(
                entity.getQuestionsJson(), new TypeReference<List<InterviewQuestionDTO>>() {});

        // 2. 提取简历文本（通用面试可能没有简历）
        String resumeText = entity.getResume() != null
                ? entity.getResume().getRawText()
                : "";

        // 3. 调用 AI 评估
        ChatClient chatClient = chatClientBuilder.build();
        log.info("调用 AI 评估: sessionId={}, 共{}题, 简历长度={}",
                sessionId, questions.size(), resumeText.length());

        InterviewReportDTO report = answerEvaluationService.evaluateInterview(
                chatClient, sessionId, resumeText, questions);

        // 4. 持久化报告到 Entity
        try {
            entity.setOverallScore(report.overallScore());
            entity.setOverallFeedback(report.overallFeedback());
            entity.setStrengthsJson(objectMapper.writeValueAsString(report.strengths()));
            entity.setImprovementsJson(objectMapper.writeValueAsString(report.improvements()));
            entity.setReferenceAnswersJson(objectMapper.writeValueAsString(report.referenceAnswers()));
            entity.setStatus(InterviewSessionEntity.SessionStatus.EVALUATED);
            sessionRepository.save(entity);
            log.info("报告已持久化: sessionId={}, score={}", sessionId, report.overallScore());
        } catch (Exception e) {
            log.error("序列化报告失败: sessionId={}, error={}", sessionId, e.getMessage(), e);
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "保存评估报告失败");
        }

        // 5. 更新 Redis 缓存状态
        try {
            sessionCache.updateSessionStatus(sessionId, SessionStatus.EVALUATED);
        } catch (Exception e) {
            log.warn("更新 Redis 缓存状态失败（不影响主流程）: sessionId={}", sessionId);
        }

        return report;
    }

    /**
     * 从已评估的实体反序列化报告。
     * <p>
     * 将 Entity 中 JSON 字段反序列化，结合 answers 表的数据，
     * 组装完整的 {@link InterviewReportDTO}。
     */
    private InterviewReportDTO deserializeReport(InterviewSessionEntity entity) {
        // 反序列化题目列表（用于获取题面和分类）
        List<InterviewQuestionDTO> questions = parseJsonList(
                entity.getQuestionsJson(), new TypeReference<List<InterviewQuestionDTO>>() {});

        // 反序列化优势 / 改进建议 / 参考答案
        List<String> strengths = parseJsonList(
                entity.getStrengthsJson(), new TypeReference<List<String>>() {});
        List<String> improvements = parseJsonList(
                entity.getImprovementsJson(), new TypeReference<List<String>>() {});
        List<InterviewReportDTO.ReferenceAnswer> referenceAnswers = parseJsonList(
                entity.getReferenceAnswersJson(),
                new TypeReference<List<InterviewReportDTO.ReferenceAnswer>>() {});

        // 从 DB 加载每题答案（含 score + feedback）
        List<InterviewAnswerEntity> answerEntities =
                answerRepository.findBySession_SessionIdOrderByQuestionIndex(entity.getSessionId());

        // 构建 questionDetails 和 categoryScores
        List<InterviewReportDTO.QuestionEvaluation> questionDetails = new ArrayList<>();
        java.util.Map<String, List<Integer>> categoryScoreMap = new java.util.HashMap<>();

        // 以 questions 为准遍历（确保题号连续，未回答的也显示）
        if (questions != null) {
            java.util.Map<Integer, InterviewAnswerEntity> answerMap = answerEntities.stream()
                    .collect(java.util.stream.Collectors.toMap(
                            InterviewAnswerEntity::getQuestionIndex,
                            a -> a,
                            (a1, a2) -> a1));

            for (InterviewQuestionDTO q : questions) {
                InterviewAnswerEntity answer = answerMap.get(q.questionIndex());
                int score = answer != null && answer.getScore() != null ? answer.getScore() : 0;
                String feedback = answer != null && answer.getFeedback() != null
                        ? answer.getFeedback() : "";
                String userAnswer = answer != null ? answer.getUserAnswer() : q.userAnswer();

                questionDetails.add(new InterviewReportDTO.QuestionEvaluation(
                        q.questionIndex(), q.question(), q.category(),
                        userAnswer, score, feedback));

                categoryScoreMap.computeIfAbsent(q.category(), k -> new ArrayList<>()).add(score);
            }
        }

        // 汇总 categoryScores
        List<InterviewReportDTO.CategoryScore> categoryScores = categoryScoreMap.entrySet().stream()
                .map(entry -> new InterviewReportDTO.CategoryScore(
                        entry.getKey(),
                        (int) entry.getValue().stream().mapToInt(Integer::intValue).average().orElse(0),
                        entry.getValue().size()))
                .toList();

        return new InterviewReportDTO(
                entity.getSessionId(),
                entity.getTotalQuestions() != null ? entity.getTotalQuestions() : 0,
                entity.getOverallScore() != null ? entity.getOverallScore() : 0,
                categoryScores,
                questionDetails,
                entity.getOverallFeedback(),
                strengths != null ? strengths : List.of(),
                improvements != null ? improvements : List.of(),
                referenceAnswers != null ? referenceAnswers : List.of()
        );
    }

    /**
     * 安全地反序列化 JSON 列表。
     * JSON 为 null 或空串时返回空列表，避免 NPE。
     */
    private <T> List<T> parseJsonList(String json, TypeReference<List<T>> typeRef) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, typeRef);
        } catch (Exception e) {
            log.warn("反序列化 JSON 失败: {}", e.getMessage());
            return List.of();
        }
    }

    public InterviewDetailDTO getSessionDetail(String sessionId) {
        // 1. 一次解包，避免重复 orElseThrow
        InterviewSessionEntity e = sessionRepository.findBySessionId(sessionId)
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.INTERVIEW_SESSION_NOT_FOUND, "会话不存在: " + sessionId));

        // 2. 反序列化 JSON 字段
        List<InterviewQuestionDTO> questions = parseJsonList(
                e.getQuestionsJson(), new TypeReference<List<InterviewQuestionDTO>>() {});
        List<String> strengths = parseJsonList(
                e.getStrengthsJson(), new TypeReference<List<String>>() {});
        List<String> improvements = parseJsonList(
                e.getImprovementsJson(), new TypeReference<List<String>>() {});
        List<InterviewReportDTO.ReferenceAnswer> referenceAnswers = parseJsonList(
                e.getReferenceAnswersJson(),
                new TypeReference<List<InterviewReportDTO.ReferenceAnswer>>() {});

        // 3. 将 InterviewAnswerEntity 列表转为 AnswerDetailDTO 列表
        List<InterviewDetailDTO.AnswerDetailDTO> answerDetails = e.getAnswers().stream()
                .map(a -> new InterviewDetailDTO.AnswerDetailDTO(
                        a.getQuestionIndex(),
                        a.getQuestion(),
                        a.getCategory(),
                        a.getUserAnswer(),
                        a.getScore(),
                        a.getFeedback(),
                        a.getReferenceAnswer(),
                        parseJsonList(a.getKeyPointsJson(),
                                new TypeReference<List<String>>() {}),
                        a.getAnsweredAt()
                ))
                .toList();

        // 4. 组装 DTO（枚举转 String，泛型转 Object 匹配 DTO 定义）
        return new InterviewDetailDTO(
                e.getId(),
                e.getSessionId(),
                e.getTotalQuestions(),
                e.getStatus().name(),
                e.getEvaluateStatus() != null ? e.getEvaluateStatus().name() : null,
                e.getEvaluateError(),
                e.getOverallScore(),
                e.getOverallFeedback(),
                e.getCreatedAt(),
                e.getCompletedAt(),
                new ArrayList<Object>(questions),
                strengths,
                improvements,
                new ArrayList<Object>(referenceAnswers),
                answerDetails
        );
    }

    /**
     * 删除面试会话（级联删除关联的答案）。
     * <p>
     * {@link InterviewSessionEntity} 配置了 {@code CascadeType.ALL + orphanRemoval = true}，
     * 删除会话时 JPA 会自动删除所有关联的答案。
     *
     * @param sessionId 会话唯一标识
     * @throws BusinessException 会话不存在
     */
    @Transactional(rollbackFor = Exception.class)
    public void deleteSession(String sessionId) {
        InterviewSessionEntity entity = sessionRepository.findBySessionId(sessionId)
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.INTERVIEW_SESSION_NOT_FOUND, "会话不存在: " + sessionId));

        // 删除 DB 记录（级联删除 answers）
        sessionRepository.delete(entity);
        log.info("已删除面试会话: sessionId={}", sessionId);

        // 清理 Redis 缓存
        try {
            sessionCache.deleteSession(sessionId);
        } catch (Exception e) {
            log.warn("清理 Redis 缓存失败（不影响主流程）: sessionId={}", sessionId);
        }
    }

    /**
     * 导出面试报告为 PDF。
     *
     * @param sessionId 会话唯一标识
     * @return PDF 字节数组
     * @throws BusinessException 会话不存在或导出失败
     */
    public byte[] exportSessionPdf(String sessionId) {
        InterviewSessionEntity entity = sessionRepository.findBySessionIdWithResume(sessionId)
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.INTERVIEW_SESSION_NOT_FOUND, "会话不存在: " + sessionId));

        if (entity.getOverallScore() == null) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "面试尚未评估，无法导出报告");
        }

        try {
            return pdfExportService.exportInterviewReport(entity);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("导出 PDF 失败: sessionId={}", sessionId, e);
            throw new BusinessException(ErrorCode.EXPORT_PDF_FAILED, "导出 PDF 失败");
        }
    }

    /**
     * AI 原始题目的中间结构。
     * <p>
     * 用于解析 LLM 返回的 JSON 数组，之后再转为 {@link InterviewQuestionDTO}。
     * 使用独立的 record 而非直接解析 DTO，因为 AI 输出的字段名/结构可能不精确，
     * 用中间结构更容易做兼容处理。
     */
    private record RawQuestion(
            String question,
            String type,
            String category,
            String topicSummary
    ) {
        // Jackson 反序列化时字段名不匹配时用 @JsonProperty，这里保持和 AI 输出一致
    }

}
