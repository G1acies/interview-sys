package interview.guide.modules.interview.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.interview.model.CreateInterviewRequest;
import interview.guide.modules.interview.model.InterviewQuestionDTO;
import interview.guide.modules.interview.model.InterviewSessionDTO;
import interview.guide.modules.interview.model.InterviewSessionEntity;
import interview.guide.modules.interview.model.SessionListItemDTO;
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
            entity.setQuestionsJson(objectMapper.writeValueAsString(questions));
        } catch (Exception e) {
            log.error("序列化题目列表失败: {}", e.getMessage(), e);
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "保存题目失败");
        }

        InterviewSessionEntity saved = sessionRepository.save(entity);
        log.info("面试会话已持久化: sessionId={}, id={}", sessionId, saved.getId());

        // 5. 返回 DTO（questions 中包含完整题目列表）
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
    private List<InterviewQuestionDTO> generateQuestionsByAI(
            String resumeText, int questionCount, String skillId, String difficulty) {

        ChatClient chatClient = chatClientBuilder.build();

        // 组装 Prompt：把 systemPrompt 和参数一起塞给 LLM
        Prompt prompt = new PromptTemplate("""
                {systemPrompt}
                
                简历内容：
                {resumeText}
                """).create(Map.of(
                "systemPrompt", SYSTEM_PROMPT
                        .replace("{questionCount}", String.valueOf(questionCount))
                        .replace("{difficulty}", difficulty != null ? difficulty : "mid"),
                "resumeText", resumeText
        ));

        String response = chatClient.prompt(prompt)
                .call()
                .content();

        log.debug("AI 原始响应: {}", response);

        if (response == null || response.isBlank()) {
            log.error("AI 返回空响应");
            throw new BusinessException(ErrorCode.AI_SERVICE_ERROR, "AI 未返回任何内容");
        }

        // 提取纯 JSON
        String json = extractJson(response);

        try {
            // 解析 AI 返回的题目列表
            List<RawQuestion> rawQuestions = objectMapper.readValue(
                    json, new TypeReference<List<RawQuestion>>() {
                    });

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
                        false,   // isFollowUp — 主问题不是追问
                        null     // parentQuestionIndex — 主问题无父节点
                ));
            }

            log.info("AI 生成题目完成: 请求={} 道, 实际={} 道", questionCount, result.size());
            return result;

        } catch (Exception e) {
            log.error("解析 AI 响应 JSON 失败: json={}, error={}", json, e.getMessage(), e);
            throw new BusinessException(ErrorCode.AI_STRUCTURED_OUTPUT_FAILED,
                    "AI 返回格式异常，无法解析题目: " + e.getMessage());
        }
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
