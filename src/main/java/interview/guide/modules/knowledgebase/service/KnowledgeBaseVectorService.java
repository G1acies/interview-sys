package interview.guide.modules.knowledgebase.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
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
     * 基于多个知识库进行语义相似度检索。
     * <p>
     * 优先使用 PgVectorStore 的 {@code filterExpression} 做前置过滤，
     * 如果 pgvector 的 JSON 过滤语法不支持当前表达式，降级为全量检索 + 本地 Java 过滤。
     *
     * @param query            用户查询文本
     * @param knowledgeBaseIds 目标知识库 ID 列表（null 或空表示搜索全部）
     * @param topK             返回的 top-K 条结果
     * @param minScore         相似度阈值（0~1，0 表示不限制）
     * @return 匹配的文档片段列表
     */
    public List<Document> similaritySearch(String query, List<Long> knowledgeBaseIds, int topK, double minScore) {
        log.info("向量相似度搜索: query={}, kbIds={}, topK={}, minScore={}",
            query, knowledgeBaseIds, topK, minScore);

        try {
            SearchRequest.Builder builder = SearchRequest.builder()
                .query(query)
                .topK(Math.max(topK, 1));

            if (minScore > 0) {
                builder.similarityThreshold(minScore);
            }
            if (knowledgeBaseIds != null && !knowledgeBaseIds.isEmpty()) {
                builder.filterExpression(buildKbFilterExpression(knowledgeBaseIds));
            }

            List<Document> results = vectorStore.similaritySearch(builder.build());
            if (results == null) return List.of();

            List<Document> limited = results.stream().limit(topK).collect(Collectors.toList());
            log.info("搜索完成: 找到 {} 个相关文档", limited.size());
            return limited;

        } catch (Exception e) {
            log.warn("向量搜索前置过滤失败，回退到本地过滤: {}", e.getMessage());
            return similaritySearchFallback(query, knowledgeBaseIds, topK, minScore);
        }
    }

    /**
     * 降级检索：全量搜索后在 Java 内存中按 kb_id 过滤。
     * <p>
     * 考虑到降级后少了数据库过滤，topK 放大 3 倍以覆盖更多候选。
     */
    private List<Document> similaritySearchFallback(String query, List<Long> knowledgeBaseIds, int topK, double minScore) {
        SearchRequest.Builder builder = SearchRequest.builder()
            .query(query)
            .topK(Math.max(topK * 3, topK));
        if (minScore > 0) {
            builder.similarityThreshold(minScore);
        }

        List<Document> allResults = vectorStore.similaritySearch(builder.build());
        if (allResults == null || allResults.isEmpty()) return List.of();

        // 本地按 kb_id 过滤
        if (knowledgeBaseIds != null && !knowledgeBaseIds.isEmpty()) {
            allResults = allResults.stream()
                .filter(doc -> isDocInKnowledgeBases(doc, knowledgeBaseIds))
                .collect(Collectors.toList());
        }

        return allResults.stream().limit(topK).collect(Collectors.toList());
    }

    /** 判断一个文档片段是否属于指定知识库之一。 */
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

    /** 构建 pgvector filterExpression：kb_id in ['1', '2', '3']。 */
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
