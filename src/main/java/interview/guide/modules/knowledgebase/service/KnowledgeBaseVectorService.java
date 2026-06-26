package interview.guide.modules.knowledgebase.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.knowledgebase.listener.VectorizeStreamConsumer;
import interview.guide.modules.knowledgebase.repository.VectorRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TextSplitter;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 知识库向量存储服务。
 * <p>
 * 负责文档分块（Text Splitting）、向量化（Embedding）和语义检索（Similarity Search）。
 * <p>
 * <b>向量化流程（{@link #vectorizeAndStore}）：</b>
 * <ol>
 *   <li>删除该知识库的旧向量（保证幂等）</li>
 *   <li>{@link TokenTextSplitter} 按 ~800 tokens 分块</li>
 *   <li>每块标记 {@code kb_id}</li>
 *   <li>每 10 块一批调用 Embedding API → 写入 pgvector</li>
 * </ol>
 * <p>
 * <b>检索流程（{@link #similaritySearch}）：</b>
 * <ol>
 *   <li>优先用 PgVectorStore 的 filterExpression 做前置过滤</li>
 *   <li>过滤语法不支持时降级为全量检索 + 本地 Java 过滤</li>
 * </ol>
 */
@Slf4j
@Service
public class KnowledgeBaseVectorService {

    /** 阿里云 DashScope Embedding API 单次最大批处理数。 */
    private static final int MAX_BATCH_SIZE = 10;

    private final VectorStore vectorStore;
    private final TextSplitter textSplitter;
    private final VectorRepository vectorRepository;

    public KnowledgeBaseVectorService(VectorStore vectorStore, VectorRepository vectorRepository) {
        this.vectorStore = vectorStore;
        this.vectorRepository = vectorRepository;
        // 默认配置：每个 chunk ~800 tokens，按标点/句子边界切分，无重叠
        this.textSplitter = TokenTextSplitter.builder().build();
    }

    // ==================================================================
    // 向量化
    // ==================================================================

    /**
     * 将知识库的纯文本内容向量化并存入 pgvector。
     * <p>
     * 由 {@link VectorizeStreamConsumer} 在异步任务中调用。
     * 已用 {@code @Transactional} 保证方法内操作原子性。
     *
     * @param knowledgeBaseId 知识库 ID，注入到每个 chunk 的 metadata
     * @param content         Tika 从文档中提取的纯文本
     */
    @Transactional
    public void vectorizeAndStore(Long knowledgeBaseId, String content) {
        log.info("开始向量化知识库: kbId={}, contentLength={}", knowledgeBaseId, content.length());

        try {
            // 1. 先删除该知识库已有的旧向量，保证幂等
            deleteByKnowledgeBaseId(knowledgeBaseId);

            // 2. TokenTextSplitter 将文本按 token 边界切成多个 chunk
            List<Document> chunks = textSplitter.apply(List.of(new Document(content)));
            log.info("文本分块完成: {} 个 chunks", chunks.size());

            // 3. 为每个 chunk 标记所属知识库 ID（检索时用 kb_id 做过滤）
            chunks.forEach(chunk ->
                chunk.getMetadata().put("kb_id", knowledgeBaseId.toString())
            );

            // 4. 分批调用 Embedding API（DashScope 限制单次 ≤ 10 条）
            int totalChunks = chunks.size();
            int batchCount = (totalChunks + MAX_BATCH_SIZE - 1) / MAX_BATCH_SIZE;
            log.info("开始分批向量化: 共 {} 个 chunks，分 {} 批", totalChunks, batchCount);

            for (int i = 0; i < batchCount; i++) {
                int start = i * MAX_BATCH_SIZE;
                int end = Math.min(start + MAX_BATCH_SIZE, totalChunks);
                List<Document> batch = chunks.subList(start, end);

                log.debug("第 {}/{} 批: chunks {}-{}", i + 1, batchCount, start + 1, end);
                // ★ vectorStore.add() 内部会自动调 EmbeddingModel.embed() 生成向量
                vectorStore.add(batch);
            }

            log.info("知识库向量化完成: kbId={}, chunks={}, batches={}",
                knowledgeBaseId, totalChunks, batchCount);

        } catch (Exception e) {
            log.error("向量化知识库失败: kbId={}, error={}", knowledgeBaseId, e.getMessage(), e);
            throw new BusinessException(ErrorCode.KNOWLEDGE_BASE_VECTORIZE_FAILED,
                "向量化知识库失败: " + e.getMessage());
        }
    }

    // ==================================================================
    // 语义检索
    // ==================================================================

    /**
     * RAG 的核心检索步骤：语义相似度搜索。
     * <p>
     * 输入用户的自然语言查询，从 pgvector 中找出语义最相似的文档片段。
     * 底层由 PgVectorStore 执行 PostgreSQL 的向量距离运算（余弦距离）。
     * <p>
     * ┌─────────────────────────────────────────────────────────────────┐
     * │  执行流程                                                       │
     * ├─────────────────────────────────────────────────────────────────┤
     * │ query = "HashMap ConcurrentHashMap 区别对比"                    │
     * │     + knowledgeBaseIds = [1, 2]  (只搜这两个知识库)             │
     * │     + topK = 8                   (最多返回 8 条)               │
     * │     + minScore = 0.28            (相似度低于 0.28 的丢掉)       │
     * │         │                                                        │
     * │         ▼                                                        │
     * │  构建 SearchRequest                                               │
     * │     ├──  query("HashMap ConcurrentHashMap 区别对比")             │
     * │     ├──  topK(8)                                                 │
     * │     ├──  similarityThreshold(0.28)  → 转为余弦距离阈值           │
     * │     └──  filterExpression("kb_id in ['1','2']")  → 数据库级过滤  │
     * │         │                                                        │
     * │         ▼                                                        │
     * │  PgVectorStore.similaritySearch()                                │
     * │     ↓ 生成 SQL                                                   │
     * │  SELECT content, embedding                                        │
     * │  FROM vector_store                                                │
     * │  WHERE metadata->>'kb_id' IN ('1','2')                           │
     * │    AND embedding <=> ? < 0.28    ← 余弦距离筛选                   │
     * │  ORDER BY embedding <=> ?                                        │
     * │  LIMIT 8                                                         │
     * │         │                                                        │
     * │         ▼                                                        │
     * │  返回 List<Document> (长度 ≤ 8)                                  │
     * │  每个 Document 包含:                                              │
     * │    - getText():      "ConcurrentHashMap 使用 CAS 操作..."        │
     * │    - getMetadata():  {kb_id=1, ...}                              │
     * └─────────────────────────────────────────────────────────────────┘
     * <p>
     * <b>双路过滤：</b>
     * <ol>
     *   <li><b>第一路（优先）：</b>通过 {@code filterExpression} 在 PostgreSQL 内部过滤，
     *       只从指定知识库的向量中进行检索，高效且精确。</li>
     *   <li><b>第二路（降级）：</b>如果 pgvector 版本不支持 filterExpression 语法，
     *       捕获异常后降级为全量检索 + Java 内存过滤。</li>
     * </ol>
     *
     * @param query            用户的自然语言查询（已由 Query Rewrite 改写）
     * @param knowledgeBaseIds 目标知识库 ID 列表，null 或空 = 搜索全部知识库
     * @param topK             最多返回多少条结果。实际返回 ≤ topK
     * @param minScore         最小相似度阈值（范围 0~1），
     *                         低于此值的文档片段被丢弃。
     *                         0 表示不设阈值。
     * @return 匹配的文档片段列表，按相似度降序排列
     */
    public List<Document> similaritySearch(String query, List<Long> knowledgeBaseIds, int topK, double minScore) {
        log.info("向量相似度搜索: query={}, kbIds={}, topK={}, minScore={}",
            query, knowledgeBaseIds, topK, minScore);

        try {
            SearchRequest.Builder builder = SearchRequest.builder()
                .query(query)
                .topK(Math.max(topK, 1));

            // 设置相似度阈值：PgVectorStore 内部会将其转为余弦距离
            // similarityThreshold(0.28) 表示"只返回距离 < 0.28 的结果"
            if (minScore > 0) {
                builder.similarityThreshold(minScore);
            }
            // 设置过滤条件：只搜索指定知识库的向量
            if (knowledgeBaseIds != null && !knowledgeBaseIds.isEmpty()) {
                builder.filterExpression(buildKbFilterExpression(knowledgeBaseIds));
            }

            // 执行向量检索 向量相似度，pgvector来完成
            List<Document> results = vectorStore.similaritySearch(builder.build());
            if (results == null) return List.of();

            // PgVectorStore 可能返回多于 topK 的结果，再在内存中裁剪一次
            List<Document> limited = results.stream().limit(topK).collect(Collectors.toList());
            log.info("搜索完成: 找到 {} 个相关文档", limited.size());
            return limited;

        } catch (Exception e) {
            // filterExpression 在某些 pgvector 版本中可能不兼容
            // （如 JSON 语法差异），降级到全量检索 + 本地过滤
            log.warn("向量搜索前置过滤失败，回退到本地过滤: {}", e.getMessage());
            return similaritySearchFallback(query, knowledgeBaseIds, topK, minScore);
        }
    }

    /**
     * 降级检索策略：当 filterExpression 不兼容时使用。
     * <p>
     * 不做数据库级过滤，而是搜一个更大的结果集回来，
     * 在 Java 内存中按 {@code kb_id} 做过滤。
     * <p>
     * 因为少了数据库过滤，topK 放大 3 倍，确保过滤后还能剩下足够的结果。
     * <p>
     * ┌──────────────────────────────────────────────────────┐
     * │ 主路径（filterExpression 生效）                      │
     * │   SQL: WHERE kb_id IN (...) AND 距离 < 阈值 LIMIT 8  │
     * │   精确、高效                                          │
     * ├──────────────────────────────────────────────────────┤
     * │ 降级路径（filterExpression 不兼容）                   │
     * │   SQL: WHERE 距离 < 阈值 LIMIT 24                    │
     * │   Java: stream().filter(属于指定kb).limit(8)         │
     * │   功能等价，但多传输了无用的向量数据                    │
     * └──────────────────────────────────────────────────────┘
     *
     * @param query            查询文本
     * @param knowledgeBaseIds 目标知识库 ID 列表
     * @param topK             期望返回条数（内部放大 3 倍后传给数据库）
     * @param minScore         相似度阈值
     * @return 过滤后的文档片段列表
     */
    private List<Document> similaritySearchFallback(String query, List<Long> knowledgeBaseIds, int topK, double minScore) {
        SearchRequest.Builder builder = SearchRequest.builder()
            .query(query)
            // 放大 topK 以弥补内存过滤带来的损耗
            .topK(Math.max(topK * 3, topK));
        if (minScore > 0) {
            builder.similarityThreshold(minScore);
        }

        // 全量检索（不限制知识库）
        List<Document> allResults = vectorStore.similaritySearch(builder.build());
        if (allResults == null || allResults.isEmpty()) return List.of();

        // 在 Java 内存中按 kb_id 过滤
        if (knowledgeBaseIds != null && !knowledgeBaseIds.isEmpty()) {
            allResults = allResults.stream()
                .filter(doc -> isDocInKnowledgeBases(doc, knowledgeBaseIds))
                .collect(Collectors.toList());
        }

        // 裁剪到期望的 topK 数量
        return allResults.stream().limit(topK).collect(Collectors.toList());
    }

    /**
     * 判断一个文档片段是否属于指定知识库之一。
     * <p>
     * 读取文档在向量化时标记的 {@code kb_id} metadata，
     * 检查它是否在指定的知识库 ID 列表中。
     * <p>
     * 兼容 {@code String} 和 {@code Long} 两种存储类型：
     * 向量化时存的是 String（{@code toString()}），
     * 但可能在某些情况下被反序列化为 Long。
     *
     * @param doc              文档片段
     * @param knowledgeBaseIds 目标知识库 ID 列表
     * @return true 表示该文档属于其中一个指定知识库
     */
    private boolean isDocInKnowledgeBases(Document doc, List<Long> knowledgeBaseIds) {
        Object kbId = doc.getMetadata().get("kb_id");
        if (kbId == null) return false;
        try {
            Long id = kbId instanceof Long ? (Long) kbId : Long.parseLong(kbId.toString());
            return knowledgeBaseIds.contains(id);
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /**
     * 构建 pgvector 的 filterExpression 过滤表达式。
     * <p>
     * 生成形如 {@code kb_id in ['1', '2', '3']} 的字符串，
     * 用于在 PgVectorStore 的 SQL 查询中通过 WHERE 子句过滤知识库。
     * <p>
     * {@code kb_id} 对应向量化时每个 chunk 的 metadata 中存的那个字段名。
     *
     * @param knowledgeBaseIds 知识库 ID 列表
     * @return filterExpression 字符串，如 "kb_id in ['1', '2']"
     */
    private String buildKbFilterExpression(List<Long> knowledgeBaseIds) {
        return knowledgeBaseIds.stream()
            .filter(Objects::nonNull)
            .map(id -> "'" + id + "'")
            .collect(Collectors.joining(", ", "kb_id in [", "]"));
    }

    // ==================================================================
    // 删除向量
    // ==================================================================

    /**
     * 删除指定知识库的所有向量数据。
     * <p>
     * 失败时不抛异常（仅日志），避免级联删除时阻断后续步骤。
     *
     * @param knowledgeBaseId 知识库 ID
     */
    @Transactional(rollbackFor = Exception.class)
    public void deleteByKnowledgeBaseId(Long knowledgeBaseId) {
        try {
            vectorRepository.deleteByKnowledgeBaseId(knowledgeBaseId);
        } catch (Exception e) {
            log.error("删除向量数据失败: kbId={}, error={}", knowledgeBaseId, e.getMessage(), e);
        }
    }
}
