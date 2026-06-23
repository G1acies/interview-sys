package interview.guide.modules.interview.model;

import java.util.List;

/**
 * 面试方向（Skill）DTO。
 * <p>
 * 对应前端 {@code src/api/skill.ts} 的 {@code SkillDTO} 接口。
 * 用于技能列表页和面试创建页的选择器。
 *
 * @param id          技能标识符，如 "java-backend"、"frontend"
 * @param name        显示名称
 * @param description 描述
 * @param categories  分类列表
 * @param isPreset    是否预设技能
 * @param sourceJd    JD 原文（自定义技能时有值）
 * @param persona     面试官角色设定
 * @param display     前端展示配置（图标、渐变色等）
 */
public record SkillDTO(
    String id,
    String name,
    String description,
    List<CategoryDTO> categories,
    boolean isPreset,
    String sourceJd,
    String persona,
    DisplayDTO display
) {
    public record DisplayDTO(
        String icon,
        String gradient,
        String iconBg,
        String iconColor
    ) {}
}
