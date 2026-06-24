package interview.guide.modules.knowledgebase.service;

import interview.guide.modules.knowledgebase.model.QueryRequest;
import interview.guide.modules.knowledgebase.model.QueryResponse;
import org.springframework.ai.chat.messages.Message;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.List;

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
@Service
public class KnowledgeBaseQueryService {

    /**
     * 基于单个知识库回答用户问题（同步模式）。
     */
    public String answerQuestion(Long knowledgeBaseId, String question) {
        return ""; // TODO
    }

    /**
     * 基于多个知识库回答用户问题（同步 RAG）。
     */
    public String answerQuestion(List<Long> knowledgeBaseIds, String question) {
        return ""; // TODO
    }

    /**
     * 查询知识库并返回完整响应（含知识库名称信息）。
     */
    public QueryResponse queryKnowledgeBase(QueryRequest request) {
        return null; // TODO
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
}
