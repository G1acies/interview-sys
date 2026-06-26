package interview.guide.modules.interviewschedule.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.interviewschedule.model.CreateInterviewRequest;
import interview.guide.modules.interviewschedule.model.InterviewType;
import interview.guide.modules.interviewschedule.model.ParseResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 面试邀约解析服务。
 * <p>
 * 将面试邀约文本（邮件、飞书链接等）解析为结构化的 {@link CreateInterviewRequest}。
 * <p>
 * 解析策略：<b>规则优先，AI 降级</b>。
 * <ol>
 *   <li>先用正则匹配常见格式（提取公司、时间、职位、链接）</li>
   <li>规则匹配成功且置信度高 → 直接返回</li>
 *   <li>规则匹配失败或置信度低 → 调用 LLM 做 AI 解析</li>
 * </ol>
 */
@Slf4j
@Service
public class InterviewParseService {

    private final ChatClient.Builder chatClientBuilder;
    private final ObjectMapper objectMapper;
    private final PromptTemplate aiPromptTemplate;

    // ── 规则解析：常用正则 ──

    /** 匹配公司名称：如"我是字节跳动的HR"、"来自阿里巴巴" */
    private static final Pattern COMPANY_PATTERN = Pattern.compile(
        "(?:来自|我是|我们[是叫])\\s*([\\u4e00-\\u9fa5A-Za-z0-9]+(?:[\\(（]?科技|技术|网络|信息|软件|有限)?[\\)）]?)"
    );

    /** 匹配职位：如"职位：后端开发工程师"、"岗位：Java" */
    private static final Pattern POSITION_PATTERN = Pattern.compile(
        "(?:职位|岗位|应聘|招聘)[：:冒号]?\\s*([\\u4e00-\\u9fa5A-Za-z0-9+/（）()]+)"
    );

    /** 匹配时间：如"2026年6月29日 14:00" */
    private static final Pattern TIME_PATTERN = Pattern.compile(
        "(\\d{4})\\s*年\\s*(\\d{1,2})\\s*月\\s*(\\d{1,2})\\s*日?\\s*(?:周[一二三四五六日]\\s*)?(\\d{1,2})[：:时](\\d{2})"
    );

    /** 匹配会议链接：https://... 或 http://... */
    private static final Pattern LINK_PATTERN = Pattern.compile(
        "https?://[\\w./%?#=_\\-&]+"
    );

    /** 匹配面试方式：现场/视频/电话 */
    private static final Pattern TYPE_PATTERN = Pattern.compile(
        "(现场|视频|电话|onsite|video|phone)"
    );

    /** 匹配面试轮次：如"第二轮面试"、"二面" */
    private static final Pattern ROUND_PATTERN = Pattern.compile(
        "第[\\d一二三四五六七八九十]+[轮次面]|[\\d一二三四五六七八九十]+面"
    );

    public InterviewParseService(ChatClient.Builder chatClientBuilder, ResourceLoader resourceLoader) throws IOException {
        this.chatClientBuilder = chatClientBuilder;
        this.objectMapper = new ObjectMapper();
        this.aiPromptTemplate = new PromptTemplate(
            resourceLoader.getResource("classpath:prompts/interview-schedule-parse.st")
                .getContentAsString(StandardCharsets.UTF_8)
        );
    }

    /**
     * 解析面试邀约文本。
     * <p>
     * 先走规则解析，规则无法提取足够信息时降级为 AI 解析。
     *
     * @param rawText 原始文本
     * @param source  来源平台（feishu/tencent/zoom/other），辅助解析
     * @return 解析结果
     */
    public ParseResponse parse(String rawText, String source) {
        // 1. 规则解析
        ParseResult ruleResult = parseByRule(rawText);
        if (ruleResult.confidence >= 0.6) {
            log.info("规则解析成功: confidence={}", ruleResult.confidence);
            return new ParseResponse(true, ruleResult.request, ruleResult.confidence, "rule", ruleResult.log);
        }

        // 2. 规则置信度不足 → AI 降级
        log.info("规则解析置信度不足(confidence={})，降级为 AI 解析", ruleResult.confidence);
        try {
            return parseByAI(rawText);
        } catch (Exception e) {
            log.error("AI 解析也失败了: {}", e.getMessage(), e);
            return new ParseResponse(false, null, 0, "ai", "规则和 AI 均无法解析该文本: " + ruleResult.log);
        }
    }

    // ==================================================================
    // 规则解析
    // ==================================================================

    /**
     * 纯规则解析：用正则从文本中提取信息。
     * <p>
     * 置信度 = 必填字段（公司、职位、时间）命中数 / 3：
     * <ul>
     *   <li>3/3 → 1.0，直接使用</li>
     *   <li>2/3 → 0.66，勉强可用</li>
     *   <li>≤1/3 → ≤0.33，信息不足，降级 AI</li>
     * </ul>
     */
    private ParseResult parseByRule(String rawText) {
        StringBuilder logBuilder = new StringBuilder("规则解析: ");

        String company = extractByRegex(rawText, COMPANY_PATTERN, logBuilder, "公司");
        String position = extractByRegex(rawText, POSITION_PATTERN, logBuilder, "职位");
        LocalDateTime time = extractTime(rawText, logBuilder);
        String link = extractByRegex(rawText, LINK_PATTERN, logBuilder, "链接");
        InterviewType type = extractType(rawText, logBuilder);
        Integer round = extractRound(rawText, logBuilder);

        // 计算置信度：三个必填字段（公司、职位、时间）的命中比例
        int hits = 0;
        if (company != null) hits++;
        if (position != null) hits++;
        if (time != null) hits++;
        double confidence = hits / 3.0;

        CreateInterviewRequest request = null;
        if (hits >= 2) {
            request = new CreateInterviewRequest(
                company != null ? company : "",
                position != null ? position : "",
                time != null ? time : LocalDateTime.now().plusDays(7),
                type,
                link,
                round,
                null,
                logBuilder.toString()
            );
        }

        return new ParseResult(request, confidence, logBuilder.toString());
    }

    /** 正则提取单字段。 */
    private String extractByRegex(String text, Pattern pattern, StringBuilder logBuilder, String fieldName) {
        Matcher matcher = pattern.matcher(text);
        if (matcher.find()) {
            String value = matcher.group(1).trim();
            logBuilder.append(fieldName).append("=").append(value).append("; ");
            return value;
        }
        logBuilder.append(fieldName).append("=未匹配; ");
        return null;
    }

    /** 提取面试时间。 */
    private LocalDateTime extractTime(String text, StringBuilder logBuilder) {
        Matcher matcher = TIME_PATTERN.matcher(text);
        if (matcher.find()) {
            try {
                int year = Integer.parseInt(matcher.group(1));
                int month = Integer.parseInt(matcher.group(2));
                int day = Integer.parseInt(matcher.group(3));
                int hour = Integer.parseInt(matcher.group(4));
                int minute = Integer.parseInt(matcher.group(5));
                LocalDateTime time = LocalDateTime.of(year, month, day, hour, minute);
                logBuilder.append("时间=").append(time.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)).append("; ");
                return time;
            } catch (Exception e) {
                logBuilder.append("时间=解析失败(").append(e.getMessage()).append("); ");
            }
        } else {
            logBuilder.append("时间=未匹配; ");
        }
        return null;
    }

    /** 提取面试方式。 */
    private InterviewType extractType(String text, StringBuilder logBuilder) {
        Matcher matcher = TYPE_PATTERN.matcher(text.toLowerCase());
        if (matcher.find()) {
            String match = matcher.group(1);
            InterviewType type = switch (match) {
                case "现场", "onsite" -> InterviewType.ONSITE;
                case "视频", "video" -> InterviewType.VIDEO;
                case "电话", "phone" -> InterviewType.PHONE;
                default -> null;
            };
            if (type != null) {
                logBuilder.append("方式=").append(type).append("; ");
                return type;
            }
        }
        logBuilder.append("方式=未匹配; ");
        return null;
    }

    /** 提取面试轮次。 */
    private Integer extractRound(String text, StringBuilder logBuilder) {
        Matcher matcher = ROUND_PATTERN.matcher(text);
        if (matcher.find()) {
            String match = matcher.group();
            // 中文数字转阿拉伯数字
            int round = switch (match) {
                case "一面", "第一轮" -> 1;
                case "二面", "第二轮" -> 2;
                case "三面", "第三轮" -> 3;
                case "四面", "第四轮" -> 4;
                case "五面", "第五轮" -> 5;
                default -> 1;
            };
            logBuilder.append("轮次=").append(round).append("; ");
            return round;
        }
        logBuilder.append("轮次=未匹配; ");
        return null;
    }

    // ==================================================================
    // AI 解析（降级）
    // ==================================================================

    /**
     * AI 解析：用 LLM 提取结构化 JSON。
     * <p>
     * 规则解析失败时调用，将原始文本喂给 LLM，要求返回 JSON。
     * 用 Jackson 反序列化为 {@link CreateInterviewRequest}。
     */
    private ParseResponse parseByAI(String rawText) {
        ChatClient chatClient = chatClientBuilder.build();

        String aiPrompt = aiPromptTemplate.render(Map.of("rawText", rawText));
        String json = chatClient.prompt()
            .user(aiPrompt)
            .call()
            .content();

        log.debug("AI 解析返回: {}", json);

        if (json == null || json.isBlank()) {
            return new ParseResponse(false, null, 0, "ai", "AI 返回为空");
        }

        try {
            // 清理可能的 Markdown 代码块包裹
            String cleanJson = json.trim();
            if (cleanJson.startsWith("```")) {
                cleanJson = cleanJson.replaceAll("```(?:json)?\\s*", "").trim();
            }

            CreateInterviewRequest data = objectMapper.readValue(cleanJson, CreateInterviewRequest.class);
            return new ParseResponse(true, data, 0.85, "ai", "AI 解析成功");
        } catch (Exception e) {
            log.error("AI 输出 JSON 解析失败: {}", e.getMessage());
            return new ParseResponse(false, null, 0, "ai", "AI 返回格式错误: " + e.getMessage());
        }
    }

    // ==================================================================
    // 内部数据结构
    // ==================================================================

    /** 规则解析的中间结果。 */
    private record ParseResult(CreateInterviewRequest request, double confidence, String log) {}
}
