package interview.guide.common.ai;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 结构化输出重试配置。
 * <p>
 * 控制 AI 结构化输出调用的重试策略，
 * 对应 {@code app.ai.structured-*} 配置项。
 */
@Data
@Component
@ConfigurationProperties(prefix = "app.ai")
public class StructuredOutputProperties {

    /** 最大重试次数（默认 2 次） */
    private int structuredMaxAttempts = 2;

    /** 重试时是否包含上次错误信息 */
    private boolean structuredIncludeLastError = true;

    /** 重试时是否使用修复提示词 */
    private boolean structuredRetryUseRepairPrompt = true;

    /** 重试时是否追加严格 JSON 指令 */
    private boolean structuredRetryAppendStrictJsonInstruction = true;

    /** 错误消息最大长度 */
    private int structuredErrorMessageMaxLength = 200;

    /** 是否开启 Metrics 统计 */
    private boolean structuredMetricsEnabled = false;
}
