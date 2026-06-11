package interview.guide.modules.resume.service;

import tools.jackson.databind.ObjectMapper;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.resume.model.ResumeAnalysisResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * AI 简历分析服务。
 * <p>
 * 将 Tika 提取的 rawText 发给大语言模型，提取结构化简历信息。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ResumeAnalysisService {

    private final ChatClient.Builder chatClientBuilder;
    private final ObjectMapper objectMapper;

    /** 分析简历的系统提示词 */
    private static final String SYSTEM_PROMPT = """
            你是一个资深的 HR 简历分析助手。
            请从以下简历内容中提取关键信息，返回严格的 JSON 格式，不要包含任何额外的说明文字。

            提取字段：
            - name: 姓名
            - phone: 电话号码
            - email: 邮箱
            - skills: 技能列表（数组）
            - experience: 工作经历（数组，每项含 company/role/duration/description）
            - education: 教育背景（数组，每项含 school/degree/major/duration）

            如果某字段在简历中未找到，设为 null 或空数组。
            """;

    /**
     * 分析简历文本，返回结构化结果。
     *
     * @param rawText Tika 提取的纯文本
     * @return 结构化简历信息
     */
    public ResumeAnalysisResult analyze(String rawText) {
        if (rawText == null || rawText.isBlank()) {
            throw new BusinessException(ErrorCode.RESUME_ANALYSIS_FAILED, "简历文本为空，无法分析");
        }

        log.info("开始 AI 简历分析，文本长度: {} 字符", rawText.length());

        ChatClient chatClient = chatClientBuilder.build();

        Prompt prompt = new PromptTemplate("""
                {systemPrompt}

                简历内容：
                {resumeText}
                """).create(Map.of(
                "systemPrompt", SYSTEM_PROMPT,
                "resumeText", rawText
        ));

        String response = chatClient.prompt(prompt)
                .call()
                .content();

        log.debug("AI 分析原始响应: {}", response);

        try {
            ResumeAnalysisResult result = objectMapper.readValue(response, ResumeAnalysisResult.class);
            log.info("AI 简历分析完成: 姓名={}, 技能数={}, 经历数={}, 教育数={}",
                    result.name(),
                    result.skills() != null ? result.skills().size() : 0,
                    result.experience() != null ? result.experience().size() : 0,
                    result.education() != null ? result.education().size() : 0);
            return result;
        } catch (Exception e) {
            log.error("AI 分析结果解析失败: {}", e.getMessage());
            throw new BusinessException(ErrorCode.RESUME_ANALYSIS_FAILED, "AI 分析结果解析失败");
        }
    }

    /**
     * 分析简历文本，返回 JSON 字符串（直接存入 database）。
     */
    public String analyzeToJson(String rawText) {
        ResumeAnalysisResult result = analyze(rawText);
        try {
            return objectMapper.writeValueAsString(result);
        } catch (Exception e) {
            log.error("分析结果序列化失败: {}", e.getMessage());
            throw new BusinessException(ErrorCode.RESUME_ANALYSIS_FAILED, "分析结果序列化失败");
        }
    }
}
