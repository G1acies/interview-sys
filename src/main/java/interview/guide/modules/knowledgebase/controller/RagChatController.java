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
 *   <li>流式消息发送（SSE）</li>
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
        return Result.success(null); // TODO
    }

    /** 获取会话列表（置顶优先 + 最近更新排序）。 */
    @GetMapping("/api/rag-chat/sessions")
    public Result<List<SessionListItemDTO>> listSessions() {
        return Result.success(List.of()); // TODO
    }

    /** 获取会话详情（含消息历史和关联知识库）。 */
    @GetMapping("/api/rag-chat/sessions/{sessionId}")
    public Result<SessionDetailDTO> getSessionDetail(@PathVariable Long sessionId) {
        return Result.success(null); // TODO
    }

    /** 更新会话标题。 */
    @PutMapping("/api/rag-chat/sessions/{sessionId}/title")
    public Result<Void> updateSessionTitle(
            @PathVariable Long sessionId,
            @Valid @RequestBody UpdateTitleRequest request) {
        return Result.success(null); // TODO
    }

    /** 切换会话置顶状态。 */
    @PutMapping("/api/rag-chat/sessions/{sessionId}/pin")
    public Result<Void> togglePin(@PathVariable Long sessionId) {
        return Result.success(null); // TODO
    }

    /** 更新会话关联的知识库。 */
    @PutMapping("/api/rag-chat/sessions/{sessionId}/knowledge-bases")
    public Result<Void> updateSessionKnowledgeBases(
            @PathVariable Long sessionId,
            @Valid @RequestBody UpdateKnowledgeBasesRequest request) {
        return Result.success(null); // TODO
    }

    /** 删除会话。 */
    @DeleteMapping("/api/rag-chat/sessions/{sessionId}")
    public Result<Void> deleteSession(@PathVariable Long sessionId) {
        return Result.success(null); // TODO
    }

    /**
     * 发送消息（流式 SSE）。
     * <p>
     * 流式响应设计：
     * <ol>
     *   <li>先同步保存用户消息和创建 AI 消息占位</li>
     *   <li>返回 LLM 流式响应</li>
     *   <li>流式完成后通过回调更新消息内容</li>
     * </ol>
     */
    @PostMapping(value = "/api/rag-chat/sessions/{sessionId}/messages/stream",
                 produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> sendMessageStream(
            @PathVariable Long sessionId,
            @Valid @RequestBody SendMessageRequest request) {
        return Flux.empty(); // TODO
    }
}
