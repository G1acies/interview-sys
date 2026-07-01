package interview.guide.modules.voice.controller;

import interview.guide.common.result.Result;
import interview.guide.modules.voice.dto.CreateVoiceSessionRequest;
import interview.guide.modules.voice.dto.PauseSessionRequest;
import interview.guide.modules.voice.dto.VoiceEvaluationStatusResponse;
import interview.guide.modules.voice.dto.VoiceInterviewMessageDTO;
import interview.guide.modules.voice.dto.VoiceSessionMeta;
import interview.guide.modules.voice.dto.VoiceSessionResponse;
import interview.guide.modules.voice.service.VoiceInterviewService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 语音面试 REST Controller。
 * <p>
 * ┌───────────────────────────────────────────────────────────────────────┐
 * │ 会话 CRUD（6）                                                        │
 * ├───────────────────────────────────────────────────────────────────────┤
 * │ POST   /api/voice-interview/sessions             创建语音面试会话      │
 * │ GET    /api/voice-interview/sessions              获取会话列表          │
 * │ GET    /api/voice-interview/sessions/{sessionId}  获取会话详情          │
 * │ POST   /api/voice-interview/sessions/{sessionId}/end   结束会话        │
 * │ DELETE /api/voice-interview/sessions/{sessionId}  删除会话             │
 * ├───────────────────────────────────────────────────────────────────────┤
 * │ 暂停/恢复（2）                                                        │
 * ├───────────────────────────────────────────────────────────────────────┤
 * │ PUT    /api/voice-interview/sessions/{sessionId}/pause   暂停会话      │
 * │ PUT    /api/voice-interview/sessions/{sessionId}/resume  恢复会话      │
 * ├───────────────────────────────────────────────────────────────────────┤
 * │ 消息 & 评估（3）                                                      │
 * ├───────────────────────────────────────────────────────────────────────┤
 * │ GET    /api/voice-interview/sessions/{sessionId}/messages   消息历史   │
 * │ GET    /api/voice-interview/sessions/{sessionId}/evaluation  评估状态  │
 * │ POST   /api/voice-interview/sessions/{sessionId}/evaluation  触发评估  │
 * ├───────────────────────────────────────────────────────────────────────┤
 * │ WebSocket（由 WebSocketConfig 注册）                                    │
 * ├───────────────────────────────────────────────────────────────────────┤
 * │ ws://ws/voice-interview/{sessionId}          语音通话 WebSocket      │
 * └───────────────────────────────────────────────────────────────────────┘
 * <p>
 * <b>设计说明</b>：
 * <ul>
 *   <li>本 Controller 只负责 REST 接口的请求映射，所有业务逻辑委托给 Service 层。</li>
 *   <li>WebSocket 端点由独立的 WebSocket Handler 处理（参见 {@code VoiceInterviewWebSocketHandler}）。</li>
 *   <li>评估采用异步模式：POST 触发后立即返回当前状态，前端轮询 GET 直到 COMPLETED。</li>
 * </ul>
 */
@Slf4j
@RestController
@RequestMapping("/api/voice-interview")
@RequiredArgsConstructor
public class VoiceInterviewController {

    private final VoiceInterviewService voiceInterviewService;

    // ==================================================================
    // 会话 CRUD
    // ==================================================================

    /**
     * 创建新的语音面试会话。
     * <p>
     * 根据技能方向、难度、开启的环节等配置创建一个语音面试会话。
     * 返回会话详情，前端凭 {@code webSocketUrl} 建立 WebSocket 连接。
     *
     * @param request 创建会话请求（skillId 必填）
     * @return 创建的会话详情（含 WebSocket 连接地址）
     */
    @PostMapping("/sessions")
    public Result<VoiceSessionResponse> createSession(@Valid @RequestBody CreateVoiceSessionRequest request) {
        log.info("创建语音面试会话: skillId={}, difficulty={}", request.skillId(), request.difficulty());
        return Result.success(voiceInterviewService.createSession(request));
    }

    /**
     * 获取单个会话详情。
     *
     * @param sessionId 会话 ID
     * @return 会话详情
     */
    @GetMapping("/sessions/{sessionId}")
    public Result<VoiceSessionResponse> getSession(@PathVariable Long sessionId) {
        log.info("获取语音面试会话: sessionId={}", sessionId);
        return Result.success(voiceInterviewService.getSession(sessionId));
    }

    /**
     * 获取会话列表。
     * <p>
     * 支持按用户 ID 和状态筛选（均为可选参数）。
     *
     * @param userId 用户 ID（可选，预留字段）
     * @param status 会话状态（可选：CREATED / IN_PROGRESS / PAUSED / COMPLETED / EVALUATED）
     * @return 会话元数据列表
     */
    @GetMapping("/sessions")
    public Result<List<VoiceSessionMeta>> listSessions(
        @RequestParam(required = false) String userId,
        @RequestParam(required = false) String status) {
        log.info("获取语音面试会话列表: userId={}, status={}", userId, status);
        return Result.success(voiceInterviewService.listSessions(userId, status));
    }

    /**
     * 结束语音面试会话。
     * <p>
     * 将会话状态设为 COMPLETED，记录结束时间和实际时长。
     * 前端在用户点击"结束面试"时调用此接口。
     *
     * @param sessionId 会话 ID
     */
    @PostMapping("/sessions/{sessionId}/end")
    public Result<Void> endSession(@PathVariable Long sessionId) {
        log.info("结束语音面试会话: sessionId={}", sessionId);
        voiceInterviewService.endSession(sessionId);
        return Result.success();
    }

    /**
     * 删除语音面试会话及其所有消息。
     *
     * @param sessionId 会话 ID
     */
    @DeleteMapping("/sessions/{sessionId}")
    public Result<Void> deleteSession(@PathVariable Long sessionId) {
        log.info("删除语音面试会话: sessionId={}", sessionId);
        voiceInterviewService.deleteSession(sessionId);
        return Result.success();
    }

    // ==================================================================
    // 暂停/恢复
    // ==================================================================

    /**
     * 暂停语音面试会话。
     * <p>
     * 将会话状态设为 PAUSED，记录暂停原因和暂停时间。
     * 超过一定时间未恢复的会话会被自动结束（由定时任务处理）。
     *
     * @param sessionId 会话 ID
     * @param request   暂停原因（可选，如 "user_initiated"）
     */
    @PutMapping("/sessions/{sessionId}/pause")
    public Result<Void> pauseSession(@PathVariable Long sessionId,
                                     @RequestBody(required = false) PauseSessionRequest request) {
        log.info("暂停语音面试会话: sessionId={}, reason={}", sessionId,
            request != null ? request.reason() : "user_initiated");
        voiceInterviewService.pauseSession(sessionId, request);
        return Result.success();
    }

    /**
     * 恢复已暂停的语音面试会话。
     * <p>
     * 将会话状态从 PAUSED 恢复为 IN_PROGRESS，返回更新后的会话详情（含新的 WebSocket 地址）。
     *
     * @param sessionId 会话 ID
     * @return 更新后的会话详情
     */
    @PutMapping("/sessions/{sessionId}/resume")
    public Result<VoiceSessionResponse> resumeSession(@PathVariable Long sessionId) {
        log.info("恢复语音面试会话: sessionId={}", sessionId);
        return Result.success(voiceInterviewService.resumeSession(sessionId));
    }

    // ==================================================================
    // 消息 & 评估
    // ==================================================================

    /**
     * 获取会话的消息历史。
     * <p>
     * 按序号升序返回所有消息，用于前端恢复对话记录。
     *
     * @param sessionId 会话 ID
     * @return 消息列表
     */
    @GetMapping("/sessions/{sessionId}/messages")
    public Result<List<VoiceInterviewMessageDTO>> getMessages(@PathVariable Long sessionId) {
        log.info("获取语音面试消息: sessionId={}", sessionId);
        return Result.success(voiceInterviewService.getMessages(sessionId));
    }

    /**
     * 获取面试评估状态（轮询接口）。
     * <p>
     * 前端轮询此接口直到 evaluateStatus 变为 COMPLETED 或 FAILED。
     * COMPLETED 时 evaluation 字段包含完整的评估详情。
     *
     * @param sessionId 会话 ID
     * @return 评估状态（可能还在 PENDING / PROCESSING）
     */
    @GetMapping("/sessions/{sessionId}/evaluation")
    public Result<VoiceEvaluationStatusResponse> getEvaluation(@PathVariable Long sessionId) {
        log.info("获取语音面试评估状态: sessionId={}", sessionId);
        return Result.success(voiceInterviewService.getEvaluation(sessionId));
    }

    /**
     * 触发异步面试评估。
     * <p>
     * 如果评估尚未开始，将状态设为 PENDING 并异步执行评估逻辑；
     * 如果已经在 PROCESSING 中，返回当前状态不重复触发。
     *
     * @param sessionId 会话 ID
     * @return 当前评估状态
     */
    @PostMapping("/sessions/{sessionId}/evaluation")
    public Result<VoiceEvaluationStatusResponse> generateEvaluation(@PathVariable Long sessionId) {
        log.info("触发语音面试评估: sessionId={}", sessionId);
        return Result.success(voiceInterviewService.triggerEvaluation(sessionId));
    }
}
