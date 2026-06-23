package interview.guide.modules.interview.service;

import interview.guide.common.evaluation.EvaluationReport;
import interview.guide.common.evaluation.QaRecord;
import interview.guide.common.evaluation.UnifiedEvaluationService;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.interview.model.InterviewQuestionDTO;
import interview.guide.modules.interview.model.InterviewReportDTO;
import interview.guide.modules.interview.model.InterviewReportDTO.CategoryScore;
import interview.guide.modules.interview.model.InterviewReportDTO.QuestionEvaluation;
import interview.guide.modules.interview.model.InterviewReportDTO.ReferenceAnswer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 文字面试答案评估服务。
 * <p>
 * 职责：DTO 适配器 — 将 {@link InterviewQuestionDTO} 转为通用的 {@link QaRecord}，
 * 调用 {@link UnifiedEvaluationService} 执行 AI 评估，
 * 再将通用的 {@link EvaluationReport} 转为面试模块专用的 {@link InterviewReportDTO}。
 * <p>
 * 流程：
 * <pre>
 * InterviewQuestionDTO[] → QaRecord[]
 *   → UnifiedEvaluationService.evaluate() → EvaluationReport
 *   → InterviewReportDTO
 * </pre>
 */
@Slf4j
@Service
public class AnswerEvaluationService {

    private final UnifiedEvaluationService unifiedEvaluationService;

    public AnswerEvaluationService(UnifiedEvaluationService unifiedEvaluationService) {
        this.unifiedEvaluationService = unifiedEvaluationService;
    }

    /**
     * 评估完整面试并生成报告。
     *
     * @param chatClient AI 聊天客户端
     * @param sessionId  会话唯一标识（仅用于日志）
     * @param resumeText 简历文本（可选，用于辅助评估）
     * @param questions  面试题目列表（含用户答案）
     * @return 面试评估报告
     * @throws BusinessException 评估过程中发生错误时抛出
     */
    public InterviewReportDTO evaluateInterview(
            ChatClient chatClient,
            String sessionId,
            String resumeText,
            List<InterviewQuestionDTO> questions) {

        log.info("开始评估面试: sessionId={}, 共{}题", sessionId, questions.size());

        try {
            // 1. 将面试题目转为通用问答记录
            List<QaRecord> qaRecords = questions.stream()
                    .map(q -> new QaRecord(
                            q.questionIndex(),
                            q.question(),
                            q.category(),
                            q.userAnswer()))
                    .toList();

            // 2. 调用通用评估引擎
            EvaluationReport report = unifiedEvaluationService.evaluate(
                    chatClient, sessionId, qaRecords, resumeText);

            // 3. 转为面试模块专用的 DTO
            return toInterviewReportDTO(report);

        } catch (BusinessException e) {
            // 业务异常直接透传
            throw e;
        } catch (Exception e) {
            log.error("面试评估失败: sessionId={}, error={}", sessionId, e.getMessage(), e);
            throw new BusinessException(ErrorCode.INTERVIEW_EVALUATION_FAILED,
                    "面试评估失败：" + e.getMessage());
        }
    }

    /**
     * 将通用的 {@link EvaluationReport} 转为面试模块专用的 {@link InterviewReportDTO}。
     * <p>
     * 两个类的字段结构完全一致，但分属不同的包（common 层 vs modules 层），
     * 需要逐字段映射。使用 stream 转换避免引入 MapStruct 依赖。
     */
    private InterviewReportDTO toInterviewReportDTO(EvaluationReport report) {
        return new InterviewReportDTO(
                report.sessionId(),
                report.totalQuestions(),
                report.overallScore(),
                report.categoryScores().stream()
                        .map(cs -> new CategoryScore(cs.category(), cs.score(), cs.questionCount()))
                        .toList(),
                report.questionDetails().stream()
                        .map(qe -> new QuestionEvaluation(
                                qe.questionIndex(), qe.question(), qe.category(),
                                qe.userAnswer(), qe.score(), qe.feedback()))
                        .toList(),
                report.overallFeedback(),
                report.strengths(),
                report.improvements(),
                report.referenceAnswers().stream()
                        .map(ra -> new ReferenceAnswer(
                                ra.questionIndex(), ra.question(),
                                ra.referenceAnswer(), ra.keyPoints()))
                        .toList()
        );
    }
}
