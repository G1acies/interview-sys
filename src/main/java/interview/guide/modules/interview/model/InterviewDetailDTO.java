package interview.guide.modules.interview.model;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 面试会话详情 DTO。
 * <p>
 * 用于面试历史详情页，包含会话基本信息和完整的问题/答案/评估数据。
 * 通过 {@link InterviewHistoryService#getInterviewDetail(String)} 组装。
 * <p>
 * 注意：questions、strengths、improvements、referenceAnswers 字段
 * 的原始数据以 JSON 字符串存在 {@link InterviewSessionEntity} 中，
 * Service 层反序列化后填入此 DTO。
 *
 * @param id               数据库主键
 * @param sessionId        会话唯一标识
 * @param totalQuestions   题目总数
 * @param status           会话状态
 * @param evaluateStatus   异步评估状态
 * @param evaluateError    评估错误信息
 * @param overallScore     总分
 * @param overallFeedback  总体评价
 * @param createdAt        创建时间
 * @param completedAt      完成时间
 * @param questions        题目列表（反序列化后的 JSON 数据）
 * @param strengths        优势列表
 * @param improvements     改进建议
 * @param referenceAnswers 参考答案
 * @param answers          每题答案详情
 */
public record InterviewDetailDTO(
    Long id,
    String sessionId,
    Integer totalQuestions,
    String status,
    String evaluateStatus,
    String evaluateError,
    Integer overallScore,
    String overallFeedback,
    LocalDateTime createdAt,
    LocalDateTime completedAt,
    List<Object> questions,
    List<String> strengths,
    List<String> improvements,
    List<Object> referenceAnswers,
    List<AnswerDetailDTO> answers
) {
    /**
     * 答案详情 DTO。
     * <p>
     * 包含每道题的作答内容、评分、反馈等完整信息。
     * 未回答的题目也会包含在列表中（userAnswer 为 null），
     * 确保题号连续，前端展示时不会"跳号"。
     *
     * @param questionIndex   问题索引
     * @param question        题目原文
     * @param category        题目分类
     * @param userAnswer      用户作答（null 表示未回答）
     * @param score           得分
     * @param feedback        作答反馈
     * @param referenceAnswer 参考答案
     * @param keyPoints       关键点列表
     * @param answeredAt      作答时间
     */
    public record AnswerDetailDTO(
        Integer questionIndex,
        String question,
        String category,
        String userAnswer,
        Integer score,
        String feedback,
        String referenceAnswer,
        List<String> keyPoints,
        LocalDateTime answeredAt
    ) {}
}
