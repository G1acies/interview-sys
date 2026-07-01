package interview.guide.modules.voice.service;

import interview.guide.modules.voice.dto.VoiceEvaluationDetail;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 语音面试评估服务。
 * <p>
 * 负责基于语音面试的完整对话记录，调用 LLM 生成面试评估报告。
 * 评估内容涵盖：总体评分、各题得分、优势分析、改进建议等。
 * <p>
 * 评估流程：
 * <pre>
 * 触发评估 → 拼接对话记录到 Prompt → 调用 LLM 生成结构化 JSON → 解析 → 持久化
 * </pre>
 * <p>
 * 评估为异步操作，前端通过轮询 {@code GET /evaluation} 获取最终结果。
 */
@Slf4j
@Service
public class VoiceEvaluationService {

    /**
     * 执行面试评估。
     * <p>
     * 读取指定会话的所有消息记录，拼接成对话上下文，
     * 调用 LLM 生成结构化的评估结果（{@link VoiceEvaluationDetail}）。
     * <p>
     * 评估维度：
     * <ul>
     *   <li>技术深度与准确性</li>
     *   <li>表达清晰度与逻辑性</li>
     *   <li>回答完整性</li>
     *   <li>综合表现与改进建议</li>
     * </ul>
     *
     * @param sessionId 会话 ID
     * @return 评估结果详情
     */
    public VoiceEvaluationDetail evaluate(Long sessionId) {
        // TODO: 读取消息 → 构造 Prompt → 调用 LLM → 解析 JSON → 返回
        log.info("开始评估语音面试: sessionId={}", sessionId);
        throw new UnsupportedOperationException("评估服务尚未实现");
    }
}
