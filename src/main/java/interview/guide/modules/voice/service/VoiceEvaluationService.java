package interview.guide.modules.voice.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.voice.dto.VoiceAnswerDetail;
import interview.guide.modules.voice.dto.VoiceEvaluationDetail;
import interview.guide.modules.voice.model.VoiceInterviewMessageEntity;
import interview.guide.modules.voice.model.VoiceInterviewSessionEntity;
import interview.guide.modules.voice.repository.VoiceInterviewMessageRepository;
import interview.guide.modules.voice.repository.VoiceInterviewSessionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * 语音面试评估服务。
 * <p>
 * 负责基于语音面试的完整对话记录，调用 LLM 生成面试评估报告。
 * 评估内容涵盖：总体评分、各题得分、优势分析、改进建议等。
 * <p>
 * 评估流程：
 * <pre>
 * 触发评估 → 从 DB 读取对话记录 → 拼接 Prompt → 调用 LLM 生成结构化 JSON
 *   → 解析 → 写回 Entity.evaluationJson → 标记 COMPLETED
 * </pre>
 * <p>
 * 本服务被 {@code VoiceEvaluationStreamConsumer} 异步调用，
 * 不阻塞前端请求。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VoiceEvaluationService {

    private final VoiceInterviewSessionRepository sessionRepository;
    private final VoiceInterviewMessageRepository messageRepository;
    private final ChatClient.Builder chatClientBuilder;
    private final ObjectMapper objectMapper;

    /**
     * AI 评估系统提示词。
     * <p>
     * 要求 LLM 根据语音面试的完整对话记录，给出面试评估。
     * 输出严格的 JSON 格式，不包含 markdown 标记。
     */
    private static final String EVALUATION_SYSTEM_PROMPT = """
            你是一个专业的面试评估专家。请根据以下语音模拟面试的对话记录，对用户的面试表现进行评估。

            要求：
            1. 输出 JSON 格式，不要包含 ```json 等 markdown 标记
            2. JSON 结构如下：
            {
              "overallScore": 75,
              "overallFeedback": "总体评价文本",
              "strengths": ["优势1", "优势2", "优势3"],
              "improvements": ["建议1", "建议2"],
              "answers": [
                {
                  "questionIndex": 0,
                  "question": "面试官的问题",
                  "category": "TECH",
                  "userAnswer": "用户的回答",
                  "score": 70,
                  "feedback": "针对本题的反馈",
                  "referenceAnswer": "参考答案",
                  "keyPoints": ["要点1", "要点2"]
                }
              ]
            }
            3. overallScore 范围 0-100
            4. strengths 和 improvements 各 2-3 条
            5. answers 中每道题都需要评估，score 范围 0-100
            6. 对话记录中 user 的回答是用户的、interviewer 的问题是面试官出的

            对话记录：
            {conversationLog}
            """;

    /**
     * 执行面试评估。
     * <p>
     * 由 {@code VoiceEvaluationStreamConsumer} 异步调用。
     * 评估完成后将结果写回 Entity 的 evaluationJson 字段。
     * 如果 AI 调用失败，标记为 FAILED 并记录错误原因。
     *
     * @param sessionId 会话 ID
     */
    @Transactional
    public void evaluate(Long sessionId) {
        log.info("开始 AI 评估: sessionId={}", sessionId);

        // 1. 读取会话和消息记录
        VoiceInterviewSessionEntity entity = sessionRepository.findById(sessionId).orElse(null);
        if (entity == null) {
            log.warn("评估失败：会话不存在: sessionId={}", sessionId);
            return;
        }

        List<VoiceInterviewMessageEntity> messages =
                messageRepository.findBySessionIdOrderBySequenceNumAsc(sessionId);

        if (messages.isEmpty()) {
            log.warn("评估失败：无对话记录: sessionId={}", sessionId);
            entity.setEvaluateStatus(interview.guide.common.model.AsyncTaskStatus.FAILED);
            entity.setEvaluateError("无对话记录，无法评估");
            sessionRepository.save(entity);
            return;
        }

        // 2. 构造对话记录文本
        StringBuilder conversationLog = new StringBuilder();
        for (VoiceInterviewMessageEntity msg : messages) {
            String role = "ai_text".equals(msg.getMessageType()) ? "面试官" : "用户";
            String text = msg.getAiGeneratedText() != null ? msg.getAiGeneratedText()
                    : msg.getUserRecognizedText() != null ? msg.getUserRecognizedText() : "";
            if (!text.isBlank()) {
                conversationLog.append(role).append(": ").append(text).append("\n");
            }
        }

        // 3. 调用 LLM 生成评估
        try {
            ChatClient chatClient = chatClientBuilder.build();

            String response = chatClient.prompt()
                    .user(u -> u.text("""
                            你是一个专业的面试评估专家。请根据以下语音模拟面试的对话记录，对用户的面试表现进行评估。

                            对话记录：
                            {conversationLog}

                            要求输出严格的 JSON 格式：
                            {
                              "overallScore": (0-100),
                              "overallFeedback": "总体评价",
                              "strengths": ["优势1", "优势2", "优势3"],
                              "improvements": ["建议1", "建议2"],
                              "answers": [
                                {
                                  "questionIndex": 0,
                                  "question": "面试官的问题",
                                  "category": "分类",
                                  "userAnswer": "用户的回答",
                                  "score": 70,
                                  "feedback": "反馈",
                                  "referenceAnswer": "参考答案",
                                  "keyPoints": ["要点1", "要点2"]
                                }
                              ]
                            }
                            """)
                            .param("conversationLog", conversationLog.toString()))
                    .call()
                    .content();

            if (response == null || response.isBlank()) {
                throw new BusinessException(ErrorCode.AI_SERVICE_ERROR, "AI 返回空结果");
            }

            // 4. 解析 JSON
            String json = response.strip()
                    .replaceAll("^```(?:json)?\\s*\\n?", "")
                    .replaceAll("\\n?```$", "")
                    .strip();

            VoiceEvaluationDetail detail = objectMapper.readValue(json, VoiceEvaluationDetail.class);

            if (detail == null) {
                throw new BusinessException(ErrorCode.AI_SERVICE_ERROR, "解析评估结果失败");
            }

            // 5. 写回 Entity
            entity.setEvaluationJson(objectMapper.writeValueAsString(detail));
            entity.setEvaluateStatus(interview.guide.common.model.AsyncTaskStatus.COMPLETED);
            entity.setEvaluateError(null);
            sessionRepository.save(entity);

            log.info("AI 评估完成: sessionId={}, score={}, 共{}题",
                    sessionId, detail.overallScore(),
                    detail.answers() != null ? detail.answers().size() : 0);

        } catch (Exception e) {
            log.error("AI 评估失败: sessionId={}, error={}", sessionId, e.getMessage(), e);
            entity.setEvaluateStatus(interview.guide.common.model.AsyncTaskStatus.FAILED);
            entity.setEvaluateError("评估失败: " + e.getMessage());
            sessionRepository.save(entity);
        }
    }
}
