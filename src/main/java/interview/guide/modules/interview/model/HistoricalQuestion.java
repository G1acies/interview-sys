package interview.guide.modules.interview.model;

/**
 * 历史面试题目摘要。
 * <p>
 * 用于 AI 出题时做去重：将历史面试中已问过的题目标记为"已考过"，
 * 避免 AI 重复出相同或相似的问题。
 * <p>
 * 由 {@link interview.guide.modules.interview.service.InterviewPersistenceService#getHistoricalQuestions}
 * 从最近的面试会话中提取，按分类压缩后传入 AI 提示词。
 *
 * @param question      原始题面
 * @param type          分类键，如 "MYSQL"、"JAVA"
 * @param topicSummary  知识点摘要，如 "Redis RDB/AOF 持久化对比"；
 *                      为 null 时由调用方截取 question 前若干字兜底
 */
public record HistoricalQuestion(String question, String type, String topicSummary) {}
