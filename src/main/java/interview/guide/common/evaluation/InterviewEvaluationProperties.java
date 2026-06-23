package interview.guide.common.evaluation;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 面试评估配置属性。
 * <p>
 * 控制 AI 评估的行为，包括每批评估题数、Prompt 模板路径等。
 * 对应 {@code app.interview.evaluation.*} 配置项。
 */
@Data
@Component
@ConfigurationProperties(prefix = "app.interview.evaluation")
public class InterviewEvaluationProperties {

    /** 每批评估的题目数量（默认 8 题） */
    private int batchSize = 8;

    /** 评估系统提示词路径 */
    private String systemPromptPath = "classpath:prompts/interview-evaluation-system.st";

    /** 评估用户提示词路径 */
    private String userPromptPath = "classpath:prompts/interview-evaluation-user.st";

    /** 汇总阶段系统提示词路径 */
    private String summarySystemPromptPath = "classpath:prompts/interview-evaluation-summary-system.st";

    /** 汇总阶段用户提示词路径 */
    private String summaryUserPromptPath = "classpath:prompts/interview-evaluation-summary-user.st";
}
