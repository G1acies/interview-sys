package interview.guide.modules.voice.dto;

import java.util.List;

/**
 * 语音面试单题评估详情。
 * <p>
 * 与前端 {@code VoiceAnswerDetail} 一一对应。
 *
 * @param questionIndex   问题索引
 * @param question        题目原文
 * @param category        题目分类，如 "JAVA"、"MYSQL"、"PROJECT"
 * @param userAnswer      用户回答
 * @param score           本题得分（0-100）
 * @param feedback        AI 给出的反馈
 * @param referenceAnswer 参考答案（可能为 null）
 * @param keyPoints       关键点列表（可能为 null）
 */
public record VoiceAnswerDetail(
    Integer questionIndex,
    String question,
    String category,
    String userAnswer,
    Integer score,
    String feedback,
    String referenceAnswer,
    List<String> keyPoints
) {}
