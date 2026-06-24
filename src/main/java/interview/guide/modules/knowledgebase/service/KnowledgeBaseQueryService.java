package interview.guide.modules.knowledgebase.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.knowledgebase.model.QueryRequest;
import interview.guide.modules.knowledgebase.model.QueryResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 知识库查询服务。
 * <p>
 * 基于向量搜索的 RAG 问答核心实现。
 * 工作流程：
 * <ol>
 *   <li>Query Rewrite — 结合历史对话改写用户问题</li>
 *   <li>动态参数检索 — 根据问题长度自动调整 topK/minScore</li>
 *   <li>向量检索 — 从 pgvector 检索相关文档片段</li>
 *   <li>构造 Prompt — system + context + question</li>
 *   <li>调用 LLM 生成回答（支持普通和流式两种模式）</li>
 *   <li>流式探测窗口 — 快速识别"无信息"模式，提前结束</li>
 * </ol>
 */
@Slf4j
@Service
public class KnowledgeBaseQueryService {

    private final ChatClient.Builder chatClientBuilder;
    private final KnowledgeBaseVectorService vectorService;
    private final KnowledgeBaseQueryProperties queryProperties;
    private final PromptTemplate systemPromptTemplate;
    private final PromptTemplate userPromptTemplate;
    private final PromptTemplate rewritePromptTemplate;

    public KnowledgeBaseQueryService(
            ChatClient.Builder chatClientBuilder,
            KnowledgeBaseVectorService vectorService,
            KnowledgeBaseQueryProperties queryProperties,
            ResourceLoader resourceLoader) throws IOException {
        this.chatClientBuilder = chatClientBuilder;
        this.vectorService = vectorService;
        this.queryProperties = queryProperties;
        this.systemPromptTemplate = new PromptTemplate(
                resourceLoader.getResource(queryProperties.getSystemPromptPath())
                        .getContentAsString(StandardCharsets.UTF_8));
        this.userPromptTemplate = new PromptTemplate(
                resourceLoader.getResource(queryProperties.getUserPromptPath())
                        .getContentAsString(StandardCharsets.UTF_8));
        this.rewritePromptTemplate = new PromptTemplate(
                resourceLoader.getResource(queryProperties.getRewritePromptPath())
                        .getContentAsString(StandardCharsets.UTF_8));
    }

    /**
     * 基于多个知识库回答用户问题（同步 RAG）。
     */
    public String answerQuestion(List<Long> knowledgeBaseIds, String question) {
        // 1. 构建查询上下文（Query Rewrite + 动态参数）
        QueryContext queryContext = buildQueryContext(question);

        // 2. 向量检索
        List<org.springframework.ai.document.Document> relevantDocs =
                retrieveRelevantDocs(queryContext, knowledgeBaseIds);

        // 3. 未命中
        if (relevantDocs == null || relevantDocs.isEmpty()) {
            return "抱歉，在选定的知识库中未检索到相关信息。";
        }

        // 4. 拼 context
        String context = relevantDocs.stream()
                .map(org.springframework.ai.document.Document::getText)
                .collect(java.util.stream.Collectors.joining("\n\n---\n\n"));

        // 5. LLM 回答
        ChatClient chatClient = chatClientBuilder.build();
        String systemPrompt = systemPromptTemplate.render();
        String userPrompt = buildUserPrompt(context, question);

        try {
            return chatClient.prompt()
                    .system(systemPrompt)
                    .user(userPrompt)
                    .call()
                    .content();
        } catch (Exception e) {
            log.error("知识库查询失败: {}", e.getMessage(), e);
            throw new BusinessException(ErrorCode.KNOWLEDGE_BASE_QUERY_FAILED, "知识库查询失败");
        }
    }

    /**
     * 基于单个知识库回答用户问题（同步模式）。
     */
    public String answerQuestion(Long knowledgeBaseId, String question) {
        return answerQuestion(List.of(knowledgeBaseId), question);
    }

    /**
     * 查询知识库并返回完整响应（含知识库名称信息）。
     */
    public QueryResponse queryKnowledgeBase(QueryRequest request) {
        String answer = answerQuestion(request.knowledgeBaseIds(), request.question());
        return new QueryResponse(answer, request.knowledgeBaseIds().getFirst(), "");
    }

    /**
     * 流式查询知识库（SSE，无上下文）。
     */
    public Flux<String> answerQuestionStream(List<Long> knowledgeBaseIds, String question) {
        return Flux.empty(); // TODO
    }

    /**
     * 流式查询知识库（SSE，支持多轮上下文）。
     */
    public Flux<String> answerQuestionStream(List<Long> knowledgeBaseIds, String question, List<Message> history) {
        return Flux.empty(); // TODO
    }

    // ==================================================================
    // 动态参数检索核心逻辑
    // ==================================================================

    /**
     * 构建查询上下文。
     * <p>
     * 步骤：
     * <ol>
     *   <li>Query Rewrite — LLM 改写问题，提高检索命中率</li>
     *   <li>候选队列 — 改写版优先，原问题兜底</li>
     *   <li>动态参数 — 根据问题长度决定 topK 和 minScore</li>
     * </ol>
     */
    private QueryContext buildQueryContext(String question) {
        String normalized = normalizeQuestion(question);
        String rewritten = rewriteQuestion(normalized);

        Set<String> candidates = new LinkedHashSet<>();
        candidates.add(rewritten);
        candidates.add(normalized);

        SearchParams params = resolveSearchParams(normalized);

        return new QueryContext(new ArrayList<>(candidates), params);
    }

    /**
     * 清洗问题：去首尾空格。
     */
    private String normalizeQuestion(String question) {
        return question == null ? "" : question.trim();
    }

    /**
     * Query Rewrite：用 LLM 改写问题，使其更适合检索。
     */
    private String rewriteQuestion(String question) {
        if (!queryProperties.getRewrite().isEnabled() || question.isBlank()) {
            return question;
        }
        try {
            ChatClient chatClient = chatClientBuilder.build();
            String rewritten = chatClient.prompt()
                    .user(rewritePromptTemplate.render(Map.of("question", question)))
                    .call()
                    .content();
            if (rewritten != null && !rewritten.isBlank()) {
                log.info("Query rewrite: '{}' → '{}'", question, rewritten.trim());
                return rewritten.trim();
            }
        } catch (Exception e) {
            log.warn("Query rewrite 失败，使用原问题: {}", e.getMessage());
        }
        return question;
    }

    /**
     * 动态参数检索：根据问题长度决定 topK 和 minScore。
     * <p>
     * 核心策略：
     * <ul>
     *   <li>短问题（≤4 字）— 信息少，多捞、降门槛</li>
     *   <li>中问题（5~12 字）— 标准参数</li>
     *   <li>长问题（>12 字）— 信息充足，少捞、高门槛</li>
     * </ul>
     */
    private SearchParams resolveSearchParams(String question) {
        int compactLength = question.replaceAll("\\s+", "").length();
        int shortLen = queryProperties.getSearch().getShortQueryLength();

        if (compactLength <= shortLen) {
            return new SearchParams(
                queryProperties.getSearch().getTopkShort(),
                queryProperties.getSearch().getMinScoreShort()
            );
        }
        if (compactLength <= 12) {
            return new SearchParams(
                queryProperties.getSearch().getTopkMedium(),
                queryProperties.getSearch().getMinScoreDefault()
            );
        }
        return new SearchParams(
            queryProperties.getSearch().getTopkLong(),
            queryProperties.getSearch().getMinScoreDefault()
        );
    }

    /**
     * 向量检索：用候选查询依次尝试，命中即返回。
     * <p>
     * 候选队列为 [改写版, 原版]，改写版优先——如果改写后命中率高就直接用。
     */
    private List<org.springframework.ai.document.Document> retrieveRelevantDocs(
            QueryContext queryContext, List<Long> knowledgeBaseIds) {

        for (String candidate : queryContext.candidateQueries) {
            if (candidate.isBlank()) continue;

            List<org.springframework.ai.document.Document> docs = vectorService.similaritySearch(
                candidate,
                knowledgeBaseIds,
                queryContext.searchParams.topK,
                queryContext.searchParams.minScore
            );

            log.info("检索候选 query='{}'，命中 {} 条", candidate, docs.size());
            if (docs != null && !docs.isEmpty()) {
                return docs;
            }
        }
        return List.of();
    }

    /**
     * 构建 User Prompt（context + question）。
     */
    private String buildUserPrompt(String context, String question) {
        Map<String, Object> variables = new HashMap<>();
        variables.put("context", context);
        variables.put("question", question);
        return userPromptTemplate.render(variables);
    }

    // ==================================================================
    // 内部数据类
    // ==================================================================

    /** 动态检索参数。 */
    private record SearchParams(int topK, double minScore) {}

    /** 查询上下文：候选查询列表 + 检索参数。 */
    private record QueryContext(List<String> candidateQueries, SearchParams searchParams) {}
}
