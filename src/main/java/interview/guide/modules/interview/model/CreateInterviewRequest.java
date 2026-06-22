package interview.guide.modules.interview.model;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

import java.util.List;

/**
 * 创建面试会话请求体。
 * <p>
 * 前端通过此请求创建一个新的模拟面试会话。
 * 支持两种模式：
 * <ul>
 *   <li><b>简历面试</b> — 传入 resumeId + resumeText，出题时简历题约占 60%</li>
 *   <li><b>通用面试</b> — 不传 resumeId，纯 Skill 方向题</li>
 * </ul>
 *
 * @param resumeText       简历文本内容（可选，无简历时为通用面试传空串）
 * @param questionCount    面试题目数量，范围 3-20
 * @param resumeId         关联的简历 ID（可选，无简历时不传）
 * @param forceCreate      是否强制创建新会话。true 时忽略未完成的会话直接创建
 * @param llmProvider      LLM 提供商标识，如 "dashscope"、"openai"（可选）
 * @param skillId          面试主题 ID，如 "java-backend"、"frontend"、"custom"
 * @param difficulty       难度级别：junior（初级）/ mid（中级）/ senior（高级）（可选）
 * @param customCategories 自定义分类列表（skillId="custom" 时有值，JD 解析结果）
 * @param jdText           JD 原文（自定义面试时作为出题依据）
 */
public record CreateInterviewRequest(
    String resumeText,

    @Min(value = 3, message = "题目数量最少3题")
    @Max(value = 20, message = "题目数量最多20题")
    int questionCount,

    Long resumeId,

    Boolean forceCreate,

    String llmProvider,

    @NotBlank(message = "面试主题不能为空")
    String skillId,

    String difficulty,

    List<CategoryDTO> customCategories,

    String jdText
) {

    /**
     * 面试分类 DTO。
     * <p>
     * 用于 JD 解析结果的分类定义，或在自定义面试中指定出题范围。
     * 与前端 src/api/skill.ts 的 CategoryDTO 一一对应。
     *
     * @param key      分类键，如 "JAVA"、"MYSQL"
     * @param label    显示标签，如 "Java 基础"
     * @param priority 优先级：CORE（核心）/ NORMAL（常规）/ ALWAYS_ONE（至少一题）
     * @param ref      关联的参考文件路径
     * @param shared   是否跨 Skill 共享该分类的参考内容
     */
    public record CategoryDTO(
        String key,
        String label,
        String priority,
        String ref,
        Boolean shared
    ) {}
}
