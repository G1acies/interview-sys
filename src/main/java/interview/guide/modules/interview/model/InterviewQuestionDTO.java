package interview.guide.modules.interview.model;

/**
 * 面试题 DTO。
 */
public record InterviewQuestionDTO(
        int questionIndex,
        String question,
        String type,
        String category,
        String topicSummary,
        boolean isFollowUp,
        Integer parentQuestionIndex,
        String userAnswer
) {
    public InterviewQuestionDTO withAnswer(String answer) {
        return new InterviewQuestionDTO(questionIndex, question, type, category,
                topicSummary, isFollowUp, parentQuestionIndex, answer);
    }

    public static InterviewQuestionDTO create(int index, String question, String type,
                                              String category, String topicSummary,
                                              boolean isFollowUp, Integer parentIndex) {
        return new InterviewQuestionDTO(index, question, type, category,
                topicSummary, isFollowUp, parentIndex, null);
    }
}
