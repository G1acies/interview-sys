package interview.guide.modules.voice.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

/**
 * 创建语音面试会话请求体。
 * <p>
 * 与前端 {@code CreateSessionRequest} 一一对应。
 *
 * @param skillId          面试方向 ID，如 "java-backend"
 * @param difficulty       难度级别：junior / mid / senior（可选）
 * @param roleType         面试官角色（可选，默认 "interviewer"）
 * @param customJdText     自定义 JD 文本（可选）
 * @param resumeId         关联的简历 ID（可选）
 * @param introEnabled     是否开启自我介绍环节（可选，默认 false）
 * @param techEnabled      是否开启技术问答环节（可选，默认 true）
 * @param projectEnabled   是否开启项目问答环节（可选，默认 true）
 * @param hrEnabled        是否开启 HR 问答环节（可选，默认 false）
 * @param plannedDuration  计划面试时长（分钟，可选，默认 15）
 * @param llmProvider      LLM 提供商标识（可选，默认 "dashscope"）
 */
public record CreateVoiceSessionRequest(
    @NotBlank(message = "面试方向不能为空")
    String skillId,

    String difficulty,

    String roleType,

    String customJdText,

    Long resumeId,

    Boolean introEnabled,

    Boolean techEnabled,

    Boolean projectEnabled,

    Boolean hrEnabled,

    @Min(value = 1, message = "计划时长至少1分钟")
    Integer plannedDuration,

    String llmProvider
) {}
