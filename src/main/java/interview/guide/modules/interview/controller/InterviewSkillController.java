package interview.guide.modules.interview.controller;

import interview.guide.common.result.Result;
import interview.guide.modules.interview.model.CategoryDTO;
import interview.guide.modules.interview.service.InterviewSkillService;
import interview.guide.modules.interview.model.SkillDTO;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 面试方向（Skill）管理控制器。
 * <p>
 * 提供预设技能的查询和 JD 文本解析功能。
 * 前端通过此接口获取可选面试方向和自定义技能分类。
 */
@Slf4j
@RestController
@RequestMapping("/api/interview/skills")
public class InterviewSkillController {

    private final InterviewSkillService skillService;

    public InterviewSkillController(InterviewSkillService skillService) {
        this.skillService = skillService;
    }

    /**
     * 获取所有预设面试方向。
     */
    @GetMapping
    public Result<List<SkillDTO>> listSkills() {
        return Result.success(skillService.getAllSkills());
    }

    /**
     * 获取单个面试方向详情。
     *
     * @param id 技能标识符，如 "java-backend"
     */
    @GetMapping("/{id}")
    public Result<SkillDTO> getSkill(@PathVariable String id) {
        return Result.success(skillService.getSkill(id));
    }

    /**
     * 解析 JD 文本，提取面试分类。
     * <p>
     * 前端用户在"自定义面试"中输入 JD 后调用此接口，
     * 后端通过 AI 分析 JD，返回建议的考察方向分类列表。
     *
     * @param request 包含 JD 原文的请求体
     * @return 解析出的分类列表
     */
    @PostMapping("/parse-jd")
    public Result<List<CategoryDTO>> parseJd(@Valid @RequestBody ParseJdRequest request) {
        log.info("解析 JD，长度: {}", request.jdText() != null ? request.jdText().length() : 0);
        return Result.success(skillService.parseJd(request.jdText()));
    }

    /**
     * JD 解析请求体。
     *
     * @param jdText 职位描述原文
     */
    public record ParseJdRequest(@NotBlank(message = "JD 内容不能为空") String jdText) {}
}
