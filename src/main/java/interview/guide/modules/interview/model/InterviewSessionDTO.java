package interview.guide.modules.interview.model;

import java.util.List;

/**
 * 面试会话 DTO。
 */
public record InterviewSessionDTO(
        String sessionId,
        String resumeText,
        int totalQuestions,
        int currentQuestionIndex,
        List<InterviewQuestionDTO> questions,
        SessionStatus status
) {
    public enum SessionStatus {
        CREATED,
        IN_PROGRESS,
        COMPLETED,
        EVALUATED
    }
}
