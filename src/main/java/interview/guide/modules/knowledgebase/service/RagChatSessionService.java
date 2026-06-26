package interview.guide.modules.knowledgebase.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.knowledgebase.model.KnowledgeBaseEntity;
import interview.guide.modules.knowledgebase.model.KnowledgeBaseListItemDTO;
import interview.guide.modules.knowledgebase.model.RagChatDTO.*;
import interview.guide.modules.knowledgebase.model.RagChatMessageEntity;
import interview.guide.modules.knowledgebase.model.RagChatSessionEntity;
import interview.guide.modules.knowledgebase.repository.KnowledgeBaseRepository;
import interview.guide.modules.knowledgebase.repository.RagChatMessageRepository;
import interview.guide.modules.knowledgebase.repository.RagChatSessionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

/**
 * RAG 聊天会话服务。
 * <p>
 * 提供会话的 CRUD，以及流式消息的完整生命周期管理。
 * <p>
 * <b>流式消息生命周期：</b>
 * <pre>
 * prepareStreamMessage()        ← ① 保存用户消息 + 创建 AI 占位（completed=false）
 *         │
 * getStreamAnswer()             ← ② 获取 LLM 流式响应 Flux（含历史上下文）
 *         │
 * completeStreamMessage()       ← ③ 流式完成后回填 AI 内容（completed=true）
 * </pre>
 * 这种设计保证即使流式中断了，前端也能展示"部分内容"而不会完全丢失。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RagChatSessionService {

    private final RagChatSessionRepository sessionRepository;
    private final RagChatMessageRepository messageRepository;
    private final KnowledgeBaseRepository knowledgeBaseRepository;
    private final KnowledgeBaseQueryService queryService;
    private final KnowledgeBaseQueryProperties queryProperties;

    // ==================================================================
    // 会话 CRUD
    // ==================================================================

    /**
     * 创建新会话。
     * <p>
     * 验证所有知识库 ID 有效后创建会话。
     * 标题为空时自动生成（单知识库用知识库名，多知识库用"3 个知识库对话"）。
     *
     * @param request 创建请求（知识库 ID 列表 + 可选标题）
     * @return 会话基础信息
     */
    @Transactional
    public SessionDTO createSession(CreateSessionRequest request) {
        List<KnowledgeBaseEntity> knowledgeBases = knowledgeBaseRepository
                .findAllById(request.knowledgeBaseIds());

        if (knowledgeBases.size() != request.knowledgeBaseIds().size()) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "部分知识库不存在");
        }

        RagChatSessionEntity session = new RagChatSessionEntity();
        session.setTitle(request.title() != null && !request.title().isBlank()
                ? request.title()
                : generateTitle(knowledgeBases));
        session.setKnowledgeBases(new HashSet<>(knowledgeBases));

        session = sessionRepository.save(session);

        log.info("创建 RAG 聊天会话: id={}, title={}", session.getId(), session.getTitle());

        return new SessionDTO(
            session.getId(),
            session.getTitle(),
            session.getKnowledgeBaseIds(),
            session.getCreatedAt()
        );
    }

    /**
     * 获取会话列表（置顶优先 + 最近更新排序）。
     */
    public List<SessionListItemDTO> listSessions() {
        return sessionRepository.findAllOrderByPinnedAndUpdatedAtDesc()
            .stream()
            .map(this::toSessionListItem)
            .toList();
    }

    /**
     * 获取会话详情（含消息历史和关联知识库）。
     * <p>
     * 分两次查询避免多对多 + 一对多同时 FETCH 导致的笛卡尔积。
     */
    public SessionDetailDTO getSessionDetail(Long sessionId) {
        RagChatSessionEntity session = sessionRepository
            .findByIdWithKnowledgeBases(sessionId)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "会话不存在"));

        List<RagChatMessageEntity> messages = messageRepository
            .findBySessionIdOrderByMessageOrderAsc(sessionId);

        List<KnowledgeBaseListItemDTO> kbDTOs = new ArrayList<>(session.getKnowledgeBases()).stream()
            .map(kb -> new KnowledgeBaseListItemDTO(
                kb.getId(), kb.getName(), kb.getCategory(),
                kb.getOriginalFilename(), kb.getFileSize(), kb.getContentType(),
                kb.getUploadedAt(), kb.getLastAccessedAt(),
                kb.getAccessCount(), kb.getQuestionCount(),
                kb.getVectorStatus(), kb.getVectorError(), kb.getChunkCount()))
            .toList();

        List<MessageDTO> messageDTOs = messages.stream()
            .map(m -> new MessageDTO(m.getId(), m.getTypeString(), m.getContent(), m.getCreatedAt()))
            .toList();

        return new SessionDetailDTO(
            session.getId(), session.getTitle(),
            kbDTOs, messageDTOs,
            session.getCreatedAt(), session.getUpdatedAt()
        );
    }

    // ==================================================================
    // 流式消息生命周期
    // ==================================================================

    /**
     * ① 准备流式消息。
     * <p>
     * 此方法在两个地方被调用：
     * - {@link RagChatController#sendMessageStream}：用户发送新消息
     * <p>
     * 执行操作：
     * <ol>
     *   <li>保存用户消息到数据库（{@code completed = true}）</li>
     *   <li>创建 AI 回答的占位记录（{@code content = ""}，{@code completed = false}）</li>
     *   <li>更新会话的消息计数</li>
     * </ol>
     *
     * @param sessionId 会话 ID
     * @param question  用户问题
     * @return AI 消息的 ID，用于后续 {@link #completeStreamMessage} 回填内容
     */
    @Transactional
    public Long prepareStreamMessage(Long sessionId, String question) {
        RagChatSessionEntity session = sessionRepository.findByIdWithKnowledgeBases(sessionId)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "会话不存在"));

        // 获取当前消息数量，作为新消息的起始序号
        int nextOrder = session.getMessageCount();

        // 保存用户消息
        RagChatMessageEntity userMessage = new RagChatMessageEntity();
        userMessage.setSession(session);
        userMessage.setType(RagChatMessageEntity.MessageType.USER);
        userMessage.setContent(question);
        userMessage.setMessageOrder(nextOrder);
        userMessage.setCompleted(true);
        messageRepository.save(userMessage);

        // 创建 AI 消息占位（未完成，内容为空）
        RagChatMessageEntity assistantMessage = new RagChatMessageEntity();
        assistantMessage.setSession(session);
        assistantMessage.setType(RagChatMessageEntity.MessageType.ASSISTANT);
        assistantMessage.setContent("");
        assistantMessage.setMessageOrder(nextOrder + 1);
        assistantMessage.setCompleted(false);
        assistantMessage = messageRepository.save(assistantMessage);

        // 更新会话消息数量
        session.setMessageCount(nextOrder + 2);
        sessionRepository.save(session);

        log.info("准备流式消息: sessionId={}, messageId={}", sessionId, assistantMessage.getId());
        return assistantMessage.getId();
    }

    /**
     * ② 获取流式回答（Flux）。
     * <p>
     * 加载会话的知识库 ID 和历史上下文后，委托给
     * {@link KnowledgeBaseQueryService#answerQuestionStream(List, String, List)}。
     * <p>
     * <b>历史上下文加载：</b>
     * <ul>
     *   <li>如果配置启用了历史（{@code history.enabled = true}），加载最近 N 条已完成消息</li>
     *   <li>排除当前轮的 user 消息（已被 prepareStreamMessage 保存）</li>
     *   <li>反转为正序（时间从早到晚）</li>
     * </ul>
     *
     * @param sessionId 会话 ID
     * @param question  用户问题
     * @return 流式回答 Flux
     */
    public Flux<String> getStreamAnswer(Long sessionId, String question) {
        RagChatSessionEntity session = sessionRepository.findByIdWithKnowledgeBases(sessionId)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "会话不存在"));

        List<Long> kbIds = session.getKnowledgeBaseIds();
        List<Message> history = queryProperties.getHistory().isEnabled()
                ? loadHistoryMessages(sessionId) : List.of();

        log.info("加载历史上下文: sessionId={}, historySize={}", sessionId, history.size());
        return queryService.answerQuestionStream(kbIds, question, history);
    }

    /**
     * ③ 流式响应完成后更新 AI 消息内容。
     * <p>
     * 无论成功还是失败都会调用此方法：
     * <ul>
     *   <li>成功：写入完整回答内容</li>
     *   <li>失败：写入已收到的部分内容 + 错误提示</li>
     * </ul>
     *
     * @param messageId AI 消息 ID（由 prepareStreamMessage 返回）
     * @param content   完整的 AI 回答内容
     */
    @Transactional
    public void completeStreamMessage(Long messageId, String content) {
        RagChatMessageEntity message = messageRepository.findById(messageId)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "消息不存在"));

        message.setContent(content);
        message.setCompleted(true);
        messageRepository.save(message);

        log.info("完成流式消息: messageId={}, contentLength={}", messageId, content.length());
    }

    // ==================================================================
    // 会话设置
    // ==================================================================

    /** 更新会话标题。 */
    @Transactional
    public void updateSessionTitle(Long sessionId, String title) {
        RagChatSessionEntity session = sessionRepository.findById(sessionId)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "会话不存在"));
        session.setTitle(title);
        sessionRepository.save(session);
    }

    /** 切换会话置顶状态。 */
    @Transactional
    public void togglePin(Long sessionId) {
        RagChatSessionEntity session = sessionRepository.findById(sessionId)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "会话不存在"));
        Boolean current = session.getIsPinned() != null ? session.getIsPinned() : false;
        session.setIsPinned(!current);
        sessionRepository.save(session);
    }

    /** 更新会话关联的知识库。 */
    @Transactional
    public void updateSessionKnowledgeBases(Long sessionId, List<Long> knowledgeBaseIds) {
        RagChatSessionEntity session = sessionRepository.findById(sessionId)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "会话不存在"));
        List<KnowledgeBaseEntity> knowledgeBases = knowledgeBaseRepository.findAllById(knowledgeBaseIds);
        session.setKnowledgeBases(new HashSet<>(knowledgeBases));
        sessionRepository.save(session);
    }

    /** 删除会话。 */
    @Transactional
    public void deleteSession(Long sessionId) {
        if (!sessionRepository.existsById(sessionId)) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "会话不存在");
        }
        sessionRepository.deleteById(sessionId);
    }

    // ==================================================================
    // 私有方法
    // ==================================================================

    /**
     * 加载会话中最近的历史消息作为多轮对话上下文。
     * <p>
     * 排除当前轮的用户消息（prepareStreamMessage 中刚保存的那条）。
     * 返回的消息按时间正序排列（从早到晚）。
     */
    private List<Message> loadHistoryMessages(Long sessionId) {
        int limit = queryProperties.getHistory().getMaxMessages() + 1;
        List<RagChatMessageEntity> recent = messageRepository
            .findRecentCompletedBySessionId(sessionId, PageRequest.of(0, limit));

        if (recent.isEmpty()) return List.of();

        // 第一条是最近的消息（DESC），即当前轮的 user 消息，排除
        List<RagChatMessageEntity> history = recent.size() <= 1
                ? List.of()
                : recent.subList(1, recent.size());

        // 反转为正序，转换为 Spring AI 的 Message 类型
        return history.reversed().stream()
            .map(m -> m.getType() == RagChatMessageEntity.MessageType.USER
                    ? (Message) new UserMessage(m.getContent())
                    : (Message) new AssistantMessage(m.getContent()))
            .toList();
    }

    /** 自动生成会话标题。 */
    private String generateTitle(List<KnowledgeBaseEntity> knowledgeBases) {
        if (knowledgeBases.isEmpty()) return "新对话";
        if (knowledgeBases.size() == 1) return knowledgeBases.getFirst().getName();
        return knowledgeBases.size() + " 个知识库对话";
    }

    /** Entity → SessionListItemDTO 转换。 */
    private SessionListItemDTO toSessionListItem(RagChatSessionEntity s) {
        List<String> kbNames = s.getKnowledgeBases().stream()
            .map(KnowledgeBaseEntity::getName)
            .toList();
        return new SessionListItemDTO(
            s.getId(), s.getTitle(), s.getMessageCount(),
            kbNames, s.getUpdatedAt(),
            s.getIsPinned() != null ? s.getIsPinned() : false
        );
    }
}
