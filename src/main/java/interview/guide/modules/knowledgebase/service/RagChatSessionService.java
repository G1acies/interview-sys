package interview.guide.modules.knowledgebase.service;

import interview.guide.modules.knowledgebase.model.RagChatDTO.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.util.List;

/**
 * RAG 聊天会话服务。
 * <p>
 * 提供会话的创建、查询、更新、删除，以及流式消息的完整生命周期管理。
 * <p>
 * <b>流式消息生命周期：</b>
 * <ol>
 *   <li>{@link #prepareStreamMessage} — 保存用户消息 + 创建 AI 消息占位（completed=false）</li>
 *   <li>{@link #getStreamAnswer} — 获取 LLM 流式响应 Flux</li>
 *   <li>{@link #completeStreamMessage} — 流式完成后更新 AI 消息内容（completed=true）</li>
 * </ol>
 */
@Service
public class RagChatSessionService {

    /**
     * 创建新会话。
     * 验证知识库存在后创建会话，标题为空时自动从知识库名称生成。
     *
     * @param request 创建会话请求（含知识库 ID 列表和可选标题）
     * @return 会话基础信息
     */
    @Transactional
    public SessionDTO createSession(CreateSessionRequest request) {
        return null; // TODO
    }

    /**
     * 获取会话列表（置顶优先 + 最近更新排序）。
     */
    public List<SessionListItemDTO> listSessions() {
        return List.of(); // TODO
    }

    /**
     * 获取会话详情（含消息历史和关联知识库列表）。
     * 分两次查询避免一对多 + 多对多同时 FETCH 导致的笛卡尔积。
     *
     * @param sessionId 会话 ID
     * @return 会话详情
     */
    public SessionDetailDTO getSessionDetail(Long sessionId) {
        return null; // TODO
    }

    /**
     * 准备流式消息。
     * <p>
     * 保存用户问题（completed=true）→ 创建 AI 回答占位（completed=false）。
     *
     * @param sessionId 会话 ID
     * @param question  用户问题
     * @return AI 消息的 ID（用于后续完成时回填内容）
     */
    @Transactional
    public Long prepareStreamMessage(Long sessionId, String question) {
        return null; // TODO
    }

    /**
     * 流式响应完成后更新 AI 消息内容。
     *
     * @param messageId AI 消息 ID（由 prepareStreamMessage 返回）
     * @param content   完整的 AI 回答内容
     */
    @Transactional
    public void completeStreamMessage(Long messageId, String content) {
        // TODO
    }

    /**
     * 获取流式回答（Flux）。
     * <p>
     * 加载会话的知识库 ID 和历史上下文后，委托给
     * {@link KnowledgeBaseQueryService#answerQuestionStream} 执行 RAG 检索 + LLM 流式生成。
     *
     * @param sessionId 会话 ID
     * @param question  用户问题
     * @return 流式回答 Flux
     */
    public Flux<String> getStreamAnswer(Long sessionId, String question) {
        return Flux.empty(); // TODO
    }

    /**
     * 更新会话标题。
     */
    @Transactional
    public void updateSessionTitle(Long sessionId, String title) {
        // TODO
    }

    /**
     * 切换会话置顶状态。
     */
    @Transactional
    public void togglePin(Long sessionId) {
        // TODO
    }

    /**
     * 更新会话关联的知识库。
     */
    @Transactional
    public void updateSessionKnowledgeBases(Long sessionId, List<Long> knowledgeBaseIds) {
        // TODO
    }

    /**
     * 删除会话。
     * <p>
     * 级联删除所有关联消息（CascadeType.ALL + orphanRemoval）。
     */
    @Transactional
    public void deleteSession(Long sessionId) {
        // TODO
    }
}
