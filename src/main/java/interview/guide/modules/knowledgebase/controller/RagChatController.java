package interview.guide.modules.knowledgebase.controller;

import interview.guide.common.result.Result;
import interview.guide.modules.knowledgebase.model.RagChatDTO.*;
import interview.guide.modules.knowledgebase.service.RagChatSessionService;
import jakarta.validation.Valid;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

import java.util.List;

/**
 * RAG 聊天会话控制器。
 * <p>
 * 提供基于知识库的智能问答会话管理 API，包括：
 * <ul>
 *   <li>会话 CRUD（创建 / 列表 / 详情 / 删除）</li>
 *   <li>会话设置（标题修改 / 置顶切换 / 关联知识库更新）</li>
 *   <li>流式消息发送（SSE）— 核心功能</li>
 * </ul>
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@Tag(name = "RAG 问答", description = "基于知识库的智能问答会话")
public class RagChatController {

    private final RagChatSessionService sessionService;

    /** 创建新会话。 */
    @PostMapping("/api/rag-chat/sessions")
    public Result<SessionDTO> createSession(@Valid @RequestBody CreateSessionRequest request) {
        return Result.success(sessionService.createSession(request));
    }

    /** 获取会话列表（置顶优先 + 最近更新排序）。 */
    @GetMapping("/api/rag-chat/sessions")
    public Result<List<SessionListItemDTO>> listSessions() {
        return Result.success(sessionService.listSessions());
    }

    /** 获取会话详情（含消息历史和关联知识库）。 */
    @GetMapping("/api/rag-chat/sessions/{sessionId}")
    public Result<SessionDetailDTO> getSessionDetail(@PathVariable Long sessionId) {
        return Result.success(sessionService.getSessionDetail(sessionId));
    }

    /** 更新会话标题。 */
    @PutMapping("/api/rag-chat/sessions/{sessionId}/title")
    public Result<Void> updateSessionTitle(
            @PathVariable Long sessionId,
            @Valid @RequestBody UpdateTitleRequest request) {
        sessionService.updateSessionTitle(sessionId, request.title());
        return Result.success(null);
    }

    /** 切换会话置顶状态。 */
    @PutMapping("/api/rag-chat/sessions/{sessionId}/pin")
    public Result<Void> togglePin(@PathVariable Long sessionId) {
        sessionService.togglePin(sessionId);
        return Result.success(null);
    }

    /** 更新会话关联的知识库。 */
    @PutMapping("/api/rag-chat/sessions/{sessionId}/knowledge-bases")
    public Result<Void> updateSessionKnowledgeBases(
            @PathVariable Long sessionId,
            @Valid @RequestBody UpdateKnowledgeBasesRequest request) {
        sessionService.updateSessionKnowledgeBases(sessionId, request.knowledgeBaseIds());
        return Result.success(null);
    }

    /** 删除会话。 */
    @DeleteMapping("/api/rag-chat/sessions/{sessionId}")
    public Result<Void> deleteSession(@PathVariable Long sessionId) {
        sessionService.deleteSession(sessionId);
        return Result.success(null);
    }

    /**
     * 发送消息（流式 SSE）。
     * <p>
     * ┌─────────────────────────────────────────────────────────────────┐
     * │ 流式消息生命周期                                                │
     * ├─────────────────────────────────────────────────────────────────┤
     * │ ① prepareStreamMessage() — 保存用户消息，创建 AI 占位           │
     * │ ② getStreamAnswer() — 加载上下文，调 LLM 流式生成              │
     * │ ③ 流式过程中：每收到一个 token，以 ServerSentEvent 格式推送     │
     * │ ④ doOnComplete — 流结束，调用 completeStreamMessage() 回填内容  │
     * │ ⑤ doOnError — 流中断，保存已收到的部分内容 + 错误提示           │
     * └─────────────────────────────────────────────────────────────────┘
     * <p>
     * SSE 格式说明：
     * <ul>
     *   <li>每行 {@code data:内容块}，换行符转义为 {@code \\n}</li>
     *   <li>前端收到后还原换行符，拼接成完整 Markdown 文本</li>
     * </ul>
     *
     * @param sessionId 会话 ID
     * @param request   消息请求（包含用户问题）
     * @return SSE 流式响应
     */
    @PostMapping(value = "/api/rag-chat/sessions/{sessionId}/messages/stream",
                 produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> sendMessageStream(
            @PathVariable Long sessionId,
            @Valid @RequestBody SendMessageRequest request) {

        log.info("收到 RAG 聊天流式请求: sessionId={}, question={}",
            sessionId, request.question());

        // 1. 保存用户消息 + 创建 AI 消息占位，获取 AI 消息 ID
        Long messageId = sessionService.prepareStreamMessage(sessionId, request.question());

        // 2. 获取流式回答 Flux
        StringBuilder fullContent = new StringBuilder();

        return sessionService.getStreamAnswer(sessionId, request.question())
            // 每收到一个 chunk，追加到 fullContent，同时以 SSE 格式推送
            // 在每个元素发出时，执行一个“副作用”操作（不影响数据本身）。
/*
        作用：将 AI 返回的每一个文本片段（chunk）追加到一个 StringBuilder（fullContent）中。

        为什么需要：因为流式响应是逐块发出的，我们需要一个缓冲区把所有的 chunk 拼成完整的回答，以便最后保存到数据库。
*/
            .doOnNext(fullContent::append)
            .map(chunk -> ServerSentEvent.<String>builder()
                .data(chunk.replace("\n", "\\n").replace("\r", "\\r"))
                .build())
            // 流式完成后，回填完整内容到数据库
            .doOnComplete(() -> {
                sessionService.completeStreamMessage(messageId, fullContent.toString());
                log.info("RAG 聊天流式完成: sessionId={}, messageId={}", sessionId, messageId);
            })
            // 流式出错时，保存已接收的内容 + 错误提示
            .doOnError(e -> {
                String content = !fullContent.isEmpty()
                    ? fullContent.toString()
                    : "【错误】回答生成失败：" + e.getMessage();
                sessionService.completeStreamMessage(messageId, content);
                log.error("RAG 聊天流式错误: sessionId={}", sessionId, e);
            });
    }
}
