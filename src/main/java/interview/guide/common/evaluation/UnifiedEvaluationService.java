package interview.guide.common.evaluation;

import interview.guide.common.ai.StructuredOutputInvoker;
import interview.guide.common.evaluation.EvaluationReport.CategoryScore;
import interview.guide.common.evaluation.EvaluationReport.QuestionEvaluation;
import interview.guide.common.evaluation.EvaluationReport.ReferenceAnswer;
import interview.guide.common.exception.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 统一面试评估服务。
 * <p>
 * 文字面试和语音面试共用的评估逻辑：
 * <ol>
 *   <li><b>分批评估</b> — 按 {@code batchSize} 将 Q&A 分批，每批调 AI 评估</li>
 *   <li><b>逐题评分</b> — 每题给出分数（0-100）和具体反馈</li>
 *   <li><b>二次汇总</b> — 汇总所有批次结果，生成总体评价、优势和改进建议</li>
 *   <li><b>降级兜底</b> — 某个批次失败时用 0 分兜底，不阻断整体流程</li>
 * </ol>
 *
 * @see InterviewEvaluationProperties 评估配置
 * @see StructuredOutputInvoker      AI 调用重试封装
 */
@Service
public class UnifiedEvaluationService {

    private static final Logger log = LoggerFactory.getLogger(UnifiedEvaluationService.class);
    private static final int MAX_REFERENCE_CONTEXT_CHARS = 6000;

    private final PromptTemplate systemPromptTemplate;
    private final PromptTemplate userPromptTemplate;
    private final BeanOutputConverter<BatchReportDTO> outputConverter;
    private final PromptTemplate summarySystemPromptTemplate;
    private final PromptTemplate summaryUserPromptTemplate;
    private final BeanOutputConverter<SummaryDTO> summaryOutputConverter;
    private final StructuredOutputInvoker structuredOutputInvoker;
    private final int evaluationBatchSize;

    // ==================== 内部 DTO（AI 结构化输出专用） ====================

    /** 批次评估结果 — AI 一次返回的结构 */
    private record BatchReportDTO(
            int overallScore,
            String overallFeedback,
            List<String> strengths,
            List<String> improvements,
            List<QuestionEvalDTO> questionEvaluations
    ) {}

    /** 单题评估结果 */
    private record QuestionEvalDTO(
            int questionIndex,
            int score,
            String feedback,
            String referenceAnswer,
            List<String> keyPoints
    ) {}

    /** 批次处理结果（含起止索引） */
    private record BatchResult(
            int startIndex,
            int endIndex,
            BatchReportDTO report
    ) {}

    /** 二次汇总结果 */
    private record SummaryDTO(
            String overallFeedback,
            List<String> strengths,
            List<String> improvements
    ) {}

    public UnifiedEvaluationService(
            StructuredOutputInvoker structuredOutputInvoker,
            ResourceLoader resourceLoader,
            InterviewEvaluationProperties evaluationProperties) throws IOException {
        this.structuredOutputInvoker = structuredOutputInvoker;
        this.systemPromptTemplate = new PromptTemplate(loadPrompt(
                resourceLoader, evaluationProperties.getSystemPromptPath()));
        this.userPromptTemplate = new PromptTemplate(loadPrompt(
                resourceLoader, evaluationProperties.getUserPromptPath()));
        this.outputConverter = new BeanOutputConverter<>(BatchReportDTO.class);
        this.summarySystemPromptTemplate = new PromptTemplate(loadPrompt(
                resourceLoader, evaluationProperties.getSummarySystemPromptPath()));
        this.summaryUserPromptTemplate = new PromptTemplate(loadPrompt(
                resourceLoader, evaluationProperties.getSummaryUserPromptPath()));
        this.summaryOutputConverter = new BeanOutputConverter<>(SummaryDTO.class);
        this.evaluationBatchSize = Math.max(1, evaluationProperties.getBatchSize());
    }

    /**
     * 评估面试问答。
     * <p>
     * 完整流程：
     * <ol>
     *   <li>简历摘要截断（前 3000 字符）</li>
     *   <li>分批调用 AI 评估每题的 {@code score} + {@code feedback}</li>
     *   <li>合并批次结果（某批失败时用 0 分兜底）</li>
     *   <li>二次汇总生成整体评语、优势、改进建议</li>
     *   <li>组装 {@link EvaluationReport} 返回</li>
     * </ol>
     *
     * @param chatClient   AI 聊天客户端
     * @param sessionId    会话 ID（仅用于日志）
     * @param qaRecords    问答记录列表
     * @param resumeText   简历摘要（可选）
     * @return 评估报告
     */
    public EvaluationReport evaluate(
            ChatClient chatClient,
            String sessionId,
            List<QaRecord> qaRecords,
            String resumeText) {
        return evaluate(chatClient, sessionId, qaRecords, resumeText, null);
    }

    /**
     * 评估面试问答（含参考基线）。
     *
     * @param chatClient       AI 聊天客户端
     * @param sessionId        会话 ID（仅用于日志）
     * @param qaRecords        问答记录列表
     * @param resumeText       简历摘要（可选）
     * @param referenceContext 参考答案基线（可选，用于校准评分）
     * @return 评估报告
     */
    public EvaluationReport evaluate(
            ChatClient chatClient,
            String sessionId,
            List<QaRecord> qaRecords,
            String resumeText,
            String referenceContext) {
        log.info("开始评估面试: sessionId={}, 共{}题", sessionId, qaRecords.size());

        // 1. 截断超长简历
        String resumeContext = resumeText != null ? resumeText : "";
        if (resumeContext.length() > 3000) {
            resumeContext = resumeContext.substring(0, 3000) + "\n...(简历内容过长，已截断)";
        }

        // 2. 截断超长参考基线
        String referenceBaseline = referenceContext != null ? referenceContext.trim() : "";
        if (referenceBaseline.length() > MAX_REFERENCE_CONTEXT_CHARS) {
            referenceBaseline = referenceBaseline.substring(0, MAX_REFERENCE_CONTEXT_CHARS)
                    + "\n...(参考基线过长，已截断)";
        }

        // 3. 分批评估
        List<BatchResult> batchResults = evaluateInBatches(
                chatClient, sessionId, resumeContext, qaRecords, referenceBaseline);

        // 4. 合并批次结果
        List<QuestionEvalDTO> mergedEvaluations = mergeQuestionEvaluations(batchResults);
        String fallbackFeedback = mergeOverallFeedback(batchResults);
        List<String> fallbackStrengths = mergeListItems(batchResults, true);
        List<String> fallbackImprovements = mergeListItems(batchResults, false);

        // 5. 二次汇总
        SummaryDTO summary = summarizeBatchResults(
                chatClient, sessionId, resumeContext, referenceBaseline, qaRecords,
                mergedEvaluations, fallbackFeedback, fallbackStrengths, fallbackImprovements);

        // 6. 组装最终报告
        return buildReport(sessionId, qaRecords, mergedEvaluations,
                summary.overallFeedback(), summary.strengths(), summary.improvements());
    }

    // ==================== 分批评估 ====================

    /** 按 batchSize 将题目分批，每批调 AI 评估 */
    private List<BatchResult> evaluateInBatches(
            ChatClient chatClient, String sessionId,
            String resumeContext, List<QaRecord> qaRecords,
            String referenceContext) {
        List<BatchResult> results = new ArrayList<>();
        for (int start = 0; start < qaRecords.size(); start += evaluationBatchSize) {
            int end = Math.min(start + evaluationBatchSize, qaRecords.size());
            List<QaRecord> batch = qaRecords.subList(start, end);
            BatchReportDTO report = evaluateBatch(
                    chatClient, sessionId, resumeContext, referenceContext, batch);
            results.add(new BatchResult(start, end, report));
        }
        return results;
    }

    /** 评估单个批次（最多 batchSize 题） */
    private BatchReportDTO evaluateBatch(
            ChatClient chatClient, String sessionId,
            String resumeContext, String referenceContext,
            List<QaRecord> batch) {
        String qaRecordsText = buildQARecords(batch);
        String systemPrompt = systemPromptTemplate.render();

        Map<String, Object> variables = new HashMap<>();
        variables.put("resumeText", resumeContext);
        variables.put("qaRecords", qaRecordsText);
        variables.put("referenceContext",
                (referenceContext != null && !referenceContext.isBlank()) ? referenceContext : "无");
        String userPrompt = userPromptTemplate.render(variables);

        String systemPromptWithFormat = systemPrompt + "\n\n" + outputConverter.getFormat();
        try {
            return structuredOutputInvoker.invoke(
                    chatClient, systemPromptWithFormat, userPrompt, outputConverter,
                    ErrorCode.INTERVIEW_EVALUATION_FAILED, "批次评估失败：", "批次评估", log
            );
        } catch (Exception e) {
            log.error("批次评估失败: sessionId={}, batchSize={}, error={}",
                    sessionId, batch.size(), e.getMessage(), e);
            return null; // 返回 null 让合并逻辑用 0 分兜底
        }
    }

    // ==================== 合并批次结果 ====================

    /** 将各批次的 questionEvaluations 合并为完整列表，缺失的用 0 分兜底 */
    private List<QuestionEvalDTO> mergeQuestionEvaluations(List<BatchResult> batchResults) {
        List<QuestionEvalDTO> merged = new ArrayList<>();
        for (BatchResult result : batchResults) {
            int expectedSize = result.endIndex() - result.startIndex();
            List<QuestionEvalDTO> current =
                    result.report() != null && result.report().questionEvaluations() != null
                            ? result.report().questionEvaluations()
                            : List.of();
            for (int i = 0; i < expectedSize; i++) {
                if (i < current.size() && current.get(i) != null) {
                    merged.add(current.get(i));
                } else {
                    merged.add(new QuestionEvalDTO(
                            result.startIndex() + i, 0,
                            "该题未成功生成评估结果，系统按 0 分处理。", "", List.of()
                    ));
                }
            }
        }
        return merged;
    }

    /** 合并所有批次的 overallFeedback */
    private String mergeOverallFeedback(List<BatchResult> batchResults) {
        String feedback = batchResults.stream()
                .map(BatchResult::report)
                .filter(r -> r != null && r.overallFeedback() != null && !r.overallFeedback().isBlank())
                .map(BatchReportDTO::overallFeedback)
                .collect(Collectors.joining("\n\n"));
        return feedback.isBlank() ? "本次面试已完成分批评估，但未生成有效综合评语。" : feedback;
    }

    /** 合并 strengths 或 improvements（去重 + 限 8 条） */
    private List<String> mergeListItems(List<BatchResult> batchResults, boolean strengthsMode) {
        Set<String> merged = new LinkedHashSet<>();
        for (BatchResult result : batchResults) {
            BatchReportDTO report = result.report();
            if (report == null) continue;
            List<String> items = strengthsMode ? report.strengths() : report.improvements();
            if (items == null) continue;
            items.stream()
                    .filter(item -> item != null && !item.isBlank())
                    .map(String::trim)
                    .forEach(merged::add);
        }
        return merged.stream().limit(8).toList();
    }

    // ==================== 二次汇总 ====================

    /** 基于所有批次的评估结果，调用 AI 做一次精炼汇总 */
    private SummaryDTO summarizeBatchResults(
            ChatClient chatClient, String sessionId, String resumeContext, String referenceContext,
            List<QaRecord> qaRecords, List<QuestionEvalDTO> evaluations,
            String fallbackFeedback, List<String> fallbackStrengths, List<String> fallbackImprovements) {
        try {
            String summarySystem = summarySystemPromptTemplate.render();
            Map<String, Object> vars = new HashMap<>();
            vars.put("resumeText", resumeContext);
            vars.put("referenceContext",
                    (referenceContext != null && !referenceContext.isBlank()) ? referenceContext : "无");
            vars.put("categorySummary", buildCategorySummary(qaRecords, evaluations));
            vars.put("questionHighlights", buildQuestionHighlights(qaRecords, evaluations));
            vars.put("fallbackOverallFeedback", fallbackFeedback);
            vars.put("fallbackStrengths", String.join("\n", fallbackStrengths));
            vars.put("fallbackImprovements", String.join("\n", fallbackImprovements));
            String summaryUser = summaryUserPromptTemplate.render(vars);

            String systemWithFormat = summarySystem + "\n\n" + summaryOutputConverter.getFormat();
            SummaryDTO dto = structuredOutputInvoker.invoke(
                    chatClient, systemWithFormat, summaryUser, summaryOutputConverter,
                    ErrorCode.INTERVIEW_EVALUATION_FAILED, "总结评估失败：", "总结评估", log
            );

            String feedback = dto != null && dto.overallFeedback() != null && !dto.overallFeedback().isBlank()
                    ? dto.overallFeedback() : fallbackFeedback;
            List<String> strengths = sanitizeItems(dto != null ? dto.strengths() : null, fallbackStrengths);
            List<String> improvements = sanitizeItems(dto != null ? dto.improvements() : null, fallbackImprovements);
            return new SummaryDTO(feedback, strengths, improvements);
        } catch (Exception e) {
            log.warn("二次汇总评估失败，降级到批次聚合结果: sessionId={}, error={}",
                    sessionId, e.getMessage());
            return new SummaryDTO(fallbackFeedback, fallbackStrengths, fallbackImprovements);
        }
    }

    /** 如果 AI 汇总结果有效则用，否则降级到批次聚合的原始结果 */
    private List<String> sanitizeItems(List<String> primary, List<String> fallback) {
        List<String> source = (primary != null && !primary.isEmpty()) ? primary : fallback;
        if (source == null || source.isEmpty()) return List.of();
        return source.stream()
                .filter(item -> item != null && !item.isBlank())
                .map(String::trim).distinct().limit(8).toList();
    }

    // ==================== 报告组装 ====================

    /** 组装最终的 EvaluationReport */
    private EvaluationReport buildReport(
            String sessionId, List<QaRecord> qaRecords,
            List<QuestionEvalDTO> evaluations,
            String overallFeedback, List<String> strengths, List<String> improvements) {
        List<QuestionEvaluation> questionDetails = new ArrayList<>();
        List<ReferenceAnswer> referenceAnswers = new ArrayList<>();
        Map<String, List<Integer>> categoryScoresMap = new HashMap<>();

        int evalSize = evaluations != null ? evaluations.size() : 0;

        for (int i = 0; i < qaRecords.size(); i++) {
            QaRecord q = qaRecords.get(i);
            QuestionEvalDTO eval = i < evalSize ? evaluations.get(i) : null;

            boolean hasAnswer = q.userAnswer() != null && !q.userAnswer().isBlank();
            int score = hasAnswer && eval != null ? eval.score() : 0;
            String feedback = eval != null && eval.feedback() != null
                    ? eval.feedback() : "该题未成功生成评估反馈。";
            String refAnswer = eval != null && eval.referenceAnswer() != null
                    ? eval.referenceAnswer() : "";
            List<String> keyPoints = eval != null && eval.keyPoints() != null
                    ? eval.keyPoints() : List.of();

            questionDetails.add(new QuestionEvaluation(
                    q.questionIndex(), q.question(), q.category(), q.userAnswer(), score, feedback
            ));
            referenceAnswers.add(new ReferenceAnswer(
                    q.questionIndex(), q.question(), refAnswer, keyPoints
            ));
            categoryScoresMap.computeIfAbsent(q.category(), k -> new ArrayList<>()).add(score);
        }

        // 按分类聚合平均分
        List<CategoryScore> categoryScores = categoryScoresMap.entrySet().stream()
                .map(e -> new CategoryScore(
                        e.getKey(),
                        (int) e.getValue().stream().mapToInt(Integer::intValue).average().orElse(0),
                        e.getValue().size()
                ))
                .collect(Collectors.toList());

        // 总分 = 所有已回答题目的平均分
        long answeredCount = qaRecords.stream()
                .filter(q -> q.userAnswer() != null && !q.userAnswer().isBlank())
                .count();
        int overallScore = answeredCount == 0 ? 0
                : (int) questionDetails.stream()
                        .mapToInt(QuestionEvaluation::score)
                        .average().orElse(0);

        return new EvaluationReport(
                sessionId, qaRecords.size(), overallScore, categoryScores, questionDetails,
                overallFeedback,
                strengths != null ? strengths : List.of(),
                improvements != null ? improvements : List.of(),
                referenceAnswers
        );
    }

    // ==================== 工具方法 ====================

    /** 将 QaRecord 列表格式化为 AI 可读的文本 */
    private String buildQARecords(List<QaRecord> batch) {
        StringBuilder sb = new StringBuilder();
        for (QaRecord q : batch) {
            sb.append(String.format("问题%d [%s]: %s\n",
                    q.questionIndex() + 1, q.category(), q.question()));
            sb.append(String.format("回答: %s\n\n",
                    q.userAnswer() != null ? q.userAnswer() : "(未回答)"));
        }
        return sb.toString();
    }

    /** 构建按分类的得分摘要 */
    private String buildCategorySummary(List<QaRecord> qaRecords, List<QuestionEvalDTO> evaluations) {
        Map<String, List<Integer>> categoryScores = new HashMap<>();
        for (int i = 0; i < qaRecords.size(); i++) {
            QaRecord q = qaRecords.get(i);
            QuestionEvalDTO eval = i < evaluations.size() ? evaluations.get(i) : null;
            int score = 0;
            if (eval != null && q.userAnswer() != null && !q.userAnswer().isBlank()) {
                score = eval.score();
            }
            categoryScores.computeIfAbsent(q.category(), k -> new ArrayList<>()).add(score);
        }
        return categoryScores.entrySet().stream()
                .map(entry -> {
                    int avg = (int) entry.getValue().stream()
                            .mapToInt(Integer::intValue).average().orElse(0);
                    return String.format("- %s: 平均分 %d, 题数 %d",
                            entry.getKey(), avg, entry.getValue().size());
                })
                .sorted()
                .collect(Collectors.joining("\n"));
    }

    /** 构建每题高亮摘要 */
    private String buildQuestionHighlights(List<QaRecord> qaRecords, List<QuestionEvalDTO> evaluations) {
        List<String> highlights = new ArrayList<>();
        for (int i = 0; i < qaRecords.size(); i++) {
            QaRecord q = qaRecords.get(i);
            QuestionEvalDTO eval = i < evaluations.size() ? evaluations.get(i) : null;
            int score = eval != null ? eval.score() : 0;
            String feedback = eval != null && eval.feedback() != null ? eval.feedback() : "";
            String shortQ = q.question().length() > 50
                    ? q.question().substring(0, 50) + "..." : q.question();
            String shortF = feedback.length() > 80
                    ? feedback.substring(0, 80) + "..." : feedback;
            highlights.add(String.format("- Q%d | %s | 分数:%d | 反馈:%s",
                    q.questionIndex() + 1, shortQ, score, shortF));
        }
        return highlights.stream().limit(20).collect(Collectors.joining("\n"));
    }

    /** 加载 Prompt 模板文件 */
    private String loadPrompt(ResourceLoader resourceLoader, String path) throws IOException {
        Resource resource = resourceLoader.getResource(path);
        return resource.getContentAsString(StandardCharsets.UTF_8);
    }
}
