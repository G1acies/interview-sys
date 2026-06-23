package interview.guide.modules.interview.controller;

import interview.guide.common.result.Result;
import interview.guide.modules.interview.model.*;
import interview.guide.modules.interview.service.InterviewService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/interview")
@RequiredArgsConstructor
public class InterviewController {

    private final InterviewService interviewService;

    @GetMapping("/sessions")
    public Result<List<SessionListItemDTO>> listSessions() {
        return Result.success(interviewService.listSessions());
    }

    @PostMapping("/sessions")
    public Result<InterviewSessionDTO> createSession(@RequestBody CreateInterviewRequest request) {
        log.info("创建面试会话: skillId={}, questionCount={}", request.skillId(), request.questionCount());
        return Result.success(interviewService.createSession(request));
    }

    @GetMapping("/sessions/{sessionId}")
    public Result<InterviewSessionDTO> getSession(@PathVariable String sessionId) {
        log.info("获取面试会话: sessionId={}", sessionId);
        return Result.success(interviewService.getSession(sessionId));
    }

    @GetMapping("/sessions/{sessionId}/question")
    public Result<CurrentQuestionResponse> getCurrentQuestion(@PathVariable String sessionId) {
        log.info("获取当前题目: sessionId={}", sessionId);
        return Result.success(interviewService.getCurrentQuestion(sessionId));
    }

    @PostMapping("/sessions/{sessionId}/answers")
    public Result<SubmitAnswerResponse> submitAnswer(
            @PathVariable String sessionId,
            @RequestBody Map<String, Object> body) {
        Integer questionIndex = (Integer) body.get("questionIndex");
        String answer = (String) body.get("answer");
        log.info("提交答案: sessionId={}, questionIndex={}, 答案长度={}",
                sessionId, questionIndex, answer != null ? answer.length() : 0);
        return Result.success(interviewService.submitAnswer(sessionId, questionIndex, answer));
    }

    @PutMapping("/sessions/{sessionId}/answers")
    public Result<Void> saveAnswer(
            @PathVariable String sessionId,
            @RequestBody Map<String, Object> body) {
        Integer questionIndex = (Integer) body.get("questionIndex");
        String answer = (String) body.get("answer");
        log.info("暂存答案: sessionId={}, questionIndex={}, 答案长度={}",
                sessionId, questionIndex, answer != null ? answer.length() : 0);
        interviewService.saveAnswer(sessionId, questionIndex, answer);
        return Result.success();
    }

    @GetMapping("/sessions/{sessionId}/report")
    public Result<InterviewReportDTO> generateReport(@PathVariable String sessionId) {
        log.info("生成面试报告 sessionId = {}", sessionId);
        return Result.success(interviewService.generateReport(sessionId));
    }

    @PostMapping("/sessions/{sessionId}/complete")
    public Result<Void> completeInterview(@PathVariable String sessionId) {
        log.info("提前交卷: sessionId={}", sessionId);
        interviewService.completeInterview(sessionId);
        return Result.success();
    }

    @GetMapping("/sessions/{sessionId}/details")
    public Result<InterviewDetailDTO> getSessionDetail(@PathVariable String sessionId) {
        log.info("获取面试详情， sessionId = {}", sessionId);
        return Result.success(interviewService.getSessionDetail(sessionId));
    }

    @GetMapping("/sessions/{sessionId}/export")
    public ResponseEntity<byte[]> exportInterviewPdf(@PathVariable String sessionId) {
        log.info("导出面试报告 PDF: sessionId={}", sessionId);
        byte[] pdfBytes = interviewService.exportSessionPdf(sessionId);
        String filename = URLEncoder.encode("模拟面试报告_" + sessionId + ".pdf",
                java.nio.charset.StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename*=UTF-8''" + filename)
                .contentType(MediaType.APPLICATION_PDF)
                .body(pdfBytes);
    }

    @DeleteMapping("/sessions/{sessionId}")
    public Result<Void> deleteSession(@PathVariable String sessionId) {
        log.info("删除面试会话: sessionId={}", sessionId);
        interviewService.deleteSession(sessionId);
        return Result.success();
    }
}
