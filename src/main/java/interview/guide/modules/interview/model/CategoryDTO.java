package interview.guide.modules.interview.model;

/**
 * 面试分类 DTO。
 * <p>
 * 用于技能管理、JD 解析结果和自定义面试的通用分类定义。
 * 对应前端 {@code src/api/skill.ts} 的 {@code CategoryDTO} 接口。
 *
 * @param key      分类键，如 "JAVA"、"MYSQL"、"SPRING"
 * @param label    显示标签，如 "Java 基础"
 * @param priority 优先级：CORE（核心）/ NORMAL（常规）/ ALWAYS_ONE（至少一题）
 * @param ref      关联的参考文件路径（可选）
 * @param shared   是否跨 Skill 共享该分类的参考内容（可选）
 */
public record CategoryDTO(
    String key,
    String label,
    String priority,
    String ref,
    Boolean shared
) {}
