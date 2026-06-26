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
import reactor.core.publisher.Sinks;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

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

    // ==================================================================
    // 流式查询
    // ==================================================================

    /**
     * 未检索到信息时返回的固定提示。
     */
    private static final String NO_RESULT_RESPONSE = "抱歉，在选定的知识库中未检索到相关信息。请换一个更具体的关键词或补充上下文后再试。";

    /**
     * 流式探测窗口大小（前 N 个字符内检测拒答模式）。
     */
    private static final int STREAM_PROBE_CHARS = 120;

    /**
     * 流式查询知识库（SSE，无上下文）。
     * <p>
     * 流程与 {@link #answerQuestion(List, String)} 相同，但通过 {@link ChatClient#stream()}
     * 逐 token 返回，前端看到打字机效果。
     * <p>
     * 增加了<b>流式探测窗口</b>机制：收集前 120 个字符，
     * 如果 LLM 输出的是"未检索到相关信息"等拒答模式，立即截断输出固定提示。
     *
     * @param knowledgeBaseIds 知识库 ID 列表
     * @param question         用户问题
     * @return 流式 Flux，每个元素是一个文本块
     */
    public Flux<String> answerQuestionStream(List<Long> knowledgeBaseIds, String question) {
        return answerQuestionStream(knowledgeBaseIds, question, List.of());
    }

    /**
     * 流式查询知识库（SSE，支持多轮上下文）。
     * <p>
     * ┌─────────────────────────────────────────────────────────────────┐
     * │ 执行流程                                                        │
     * ├─────────────────────────────────────────────────────────────────┤
     * │ ① 校验参数（kbIds 非空、question 非空）                          │
     * │ ② Query Rewrite → 动态参数 → 向量检索                          │
     * │ ③ 无命中 → 直接返回 NO_RESULT_RESPONSE                          │
     * │ ④ 命中 → 拼 Prompt（system + history + context + question）     │
     * │ ⑤ ChatClient.stream() → 原始 Flux                               │
     * │ ⑥ 探测窗口：检查前 120 字符是否"拒答模式"                        │
     * │    ├─ 是 → 截断，输出固定提示                                     │
     * │    └─ 否 → 透传剩余内容                                           │
     * └─────────────────────────────────────────────────────────────────┘
     *
     * @param knowledgeBaseIds 知识库 ID 列表
     * @param question         用户问题
     * @param history          历史对话消息（可选），支持多轮追问
     * @return 流式 Flux，每个元素是一个文本块
     */
    public Flux<String> answerQuestionStream(List<Long> knowledgeBaseIds, String question, List<Message> history) {
        // 参数校验
        if (knowledgeBaseIds == null || knowledgeBaseIds.isEmpty() || normalizeQuestion(question).isBlank()) {
            return Flux.just(NO_RESULT_RESPONSE);
        }

        try {
            // 1. 构建查询上下文（Query Rewrite + 动态参数）
            QueryContext queryContext = buildQueryContext(question);

            // 2. 向量检索
            List<org.springframework.ai.document.Document> relevantDocs =
                    retrieveRelevantDocs(queryContext, knowledgeBaseIds);

            // 3. 未命中 → 固定提示
            if (relevantDocs == null || relevantDocs.isEmpty()) {
                return Flux.just(NO_RESULT_RESPONSE);
            }

            // 4. 拼 context
            String context = relevantDocs.stream()
                    .map(org.springframework.ai.document.Document::getText)
                    .collect(Collectors.joining("\n\n---\n\n"));

            log.info("检索到 {} 个相关文档片段", relevantDocs.size());

            // 5. 构建 Prompt 并流式调用 LLM
            String systemPrompt = systemPromptTemplate.render()
                    + "\n" + "如果知识库内容不足以回答问题，请明确告知用户信息不足，不要编造。";
            String userPrompt = buildUserPrompt(context, question);

            ChatClient chatClient = chatClientBuilder.build();

            /*
             // 第一步：创建一个 ChatClient 实例
             ChatClient chatClient = ...

             // 第二步：调用 prompt() 方法，进入"提示词构建"阶段，返回 PromptSpec
             PromptSpec promptSpec = chatClient.prompt()
                 .system(systemPrompt) // 添加系统提示词
                 .user(userQuestion);  // 添加用户问题

             // 第三步：promptSpec 提供了后续方法
             // .stream()   -> 返回 StreamSpec，准备进行流式输出
             // .call()     -> 返回 CallResponseSpec，等待同步响应
             // .toolCallbacks(...) -> 可以继续注册工具
            */
            // 类型是PromptSpec, 是 Spring AI 中 ChatClient 构建流程里一个关键的中间状态对象。
            var promptSpec = chatClient.prompt().system(systemPrompt);

            // 5a. 如果有历史上下文，注入到 prompt 中
            if (history != null && !history.isEmpty()) {
                promptSpec = promptSpec.messages(history);
            }

            // 5b. 发起流式调用 区别是.stream()
            Flux<String> rawFlux = promptSpec.user(userPrompt).stream().content();

            // 6. 探测窗口：先观察前一小段内容，快速识别拒答模式
            log.info("开始流式输出（探测窗口 {} 字符）: kbIds={}", STREAM_PROBE_CHARS, knowledgeBaseIds);
            return normalizeStreamOutput(rawFlux)
                    .doOnComplete(() -> log.info("流式输出完成: kbIds={}", knowledgeBaseIds))
                    .onErrorResume(e -> {
                        log.error("流式输出失败: kbIds={}, error={}", knowledgeBaseIds, e.getMessage(), e);
                        return Flux.just("【错误】知识库查询失败：AI 服务暂时不可用，请稍后重试。");
                    });

        } catch (Exception e) {
            log.error("知识库流式问答失败: {}", e.getMessage(), e);
            return Flux.just("【错误】知识库查询失败：" + e.getMessage());
        }
    }

    /**
     * ┌─────────────────────────────────────────────────────────────────────┐
     * │ normalizeStreamOutput 在做什么                                       │
     * ├─────────────────────────────────────────────────────────────────────┤
     * │ 问题：LLM 没有相关知识时，会输出一长串拒答文本（"抱歉，根据您提供的      │
     * │ 知识库内容，我没有找到关于xxx的相关信息..."），前端要等全部生成完才能    │
     * │ 展示，浪费带宽和用户时间。                                           │
     * │                                                                     │
     * │ 解法：前 120 字符设为"探测窗口"：                                    │
     * │  - 探测阶段：收集字符到缓冲区，逐字检查是否命中拒答模式                │
     * │  - 命中拒答 → 用 {@link #NO_RESULT_RESPONSE} 替换整段输出并结束      │
     * │  - 积累到阈值 → flush 缓冲区，进入透传阶段                           │
     * │  - 流结束仍在探测 → {@link #normalizeAnswer} 检查积累的全部内容       │
     * │                                                                     │
     * │ Reactor 实现：用 {@link Sinks.Many} 手动发射，相比 Flux.create        │
     * │ 避免了"在非阻塞上下文中调用 subscribe"的 lint 警告。                  │
     * └─────────────────────────────────────────────────────────────────────┘
     *
     * @param rawFlux LLM 返回的原始流式 Flux
     * @return 处理后的 Flux：拒答时只输出 {@link #NO_RESULT_RESPONSE}，否则原样透传
     */
    private Flux<String> normalizeStreamOutput(Flux<String> rawFlux) {
        // Sinks.Many 是 thread-safe 的 Flux 发射器，
        // 比 Flux.create 更适合在 subscribe 回调中手动 emit
        Sinks.Many<String> sink = Sinks.many().unicast().onBackpressureBuffer();

        StringBuilder probeBuffer = new StringBuilder();
        AtomicBoolean passthrough = new AtomicBoolean(false);
        AtomicBoolean completed = new AtomicBoolean(false);

        // 订阅原始流
        rawFlux.subscribe(
                //实现一个函数式接口，@Nullable java.util.function.Consumer<? super T> consume
                //每收到一个token应该做什么
            chunk -> {
                if (completed.get()) return;

                // ── 探测阶段 ──
                if (!passthrough.get()) {
                    //这里的 chunk 是原始数据流（rawFlux）推送出来的每一个独立的数据片段，通常是一段字符串文本。
                    probeBuffer.append(chunk);
                    String probeText = probeBuffer.toString();

                    // 如果匹配拒答模式 → 替换为固定提示，结束
                    if (isNoResultPattern(probeText)) {
                        completed.set(true);
                        /*tryEmitNext 是 Reactor 中 Sinks.Many 提供的一个非阻塞方法，用于手动向数据流中推送（发射）一个元素。
                        它就是把数据“丢进”一个自定义的数据管道里，让订阅了这个管道的消费者能够收到它。*/
                        sink.tryEmitNext(NO_RESULT_RESPONSE);       //tryEmitNext() 是非阻塞方法，立即返回结果状态，
                        /*作用：向 sink 发送一个完成信号，表示这个数据流已经结束，不再有新数据产生。
                        一旦调用了 tryEmitComplete()，sink 的状态变为 "已终止"。
                        订阅了这个 sink 的消费者（如前端通过 SSE 接收）会收到一个完成事件，停止等待数据。
                        此后任何尝试向该 sink 写入数据的操作都会被忽略（或返回失败状态）。*/
                        sink.tryEmitComplete();
                        return;
                    }

                    // 积累到阈值 → flush 缓冲区，进入透传
                    if (probeBuffer.length() >= STREAM_PROBE_CHARS) {
                        passthrough.set(true);
                        sink.tryEmitNext(probeText);
                        probeBuffer.setLength(0);
                    }
                    return;
                }

                // ── 透传阶段 ──
                sink.tryEmitNext(chunk);
            },
            error -> {
                if (!completed.get()) sink.tryEmitError(error);
            },
            () -> {
                if (completed.get()) return;
                // 流结束时仍在探测阶段 → 检查累积的全部内容
                if (!passthrough.get()) {
                    sink.tryEmitNext(normalizeAnswer(probeBuffer.toString()));
                }
                sink.tryEmitComplete();
            }
        );

        return sink.asFlux();
    }

    /**
     * 检查文本是否匹配拒答模式。
     * <p>
     * LLM 在知识库无相关内容时可能输出"没有找到相关信息"、"未检索到"等文本。
     * 检测到这些模式时提前截断，统一返回标准提示。
     */
    private boolean isNoResultPattern(String text) {
        String t = text.toLowerCase();
        return t.contains("没有找到相关信息")
                || t.contains("未检索到相关信息")
                || t.contains("信息不足")
                || t.contains("超出知识库范围")
                || t.contains("无法根据提供内容回答");
    }

    /**
     * 归一化回答：空文本或拒答模式 → 统一替换为标准提示。
     */
    private String normalizeAnswer(String answer) {
        if (answer == null || answer.isBlank()) return NO_RESULT_RESPONSE;
        String normalized = answer.trim();
        if (isNoResultPattern(normalized)) return NO_RESULT_RESPONSE;
        return normalized;
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
        String normalized = normalizeQuestion(question);    //去除首尾空格
        String rewritten = rewriteQuestion(normalized);     //拼接问题给llm，然后返回新的问题

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
            // render() 方法将模板中的占位符替换为实际值
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

    /**
     * 动态检索参数。
     */
    private record SearchParams(int topK, double minScore) {
    }

    /**
     * 查询上下文：候选查询列表 + 检索参数。
     */
    private record QueryContext(List<String> candidateQueries, SearchParams searchParams) {
    }
}
