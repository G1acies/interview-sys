package interview.guide.modules.interview.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.interview.model.CategoryDTO;
import interview.guide.modules.interview.model.SkillDTO;
import interview.guide.modules.interview.model.SkillDTO.DisplayDTO;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 面试方向 Skill 管理服务。
 * <p>
 * 管理预设面试方向（如 Java 后端、前端等）及其分类配置，
 * 并提供 JD 文本解析为分类的能力。
 * <p>
 * 预设 Skill 在启动时通过 {@link #loadPresetSkills()} 加载到内存中。
 */
@Slf4j
@Service
public class InterviewSkillService {

    private final ChatClient.Builder chatClientBuilder;

    /** 预设 Skill 注册表（按 id 有序） */
    private final Map<String, PresetSkill> presetRegistry = new LinkedHashMap<>();

    public InterviewSkillService(ChatClient.Builder chatClientBuilder) {
        this.chatClientBuilder = chatClientBuilder;
    }

    @PostConstruct
    void loadPresetSkills() {
        // ===== Java 后端 =====
        register("java-backend", "Java 后端", "Java 后端开发面试，涵盖 Java 基础、Spring 框架、数据库、分布式等",
                List.of(
                        new PresetCategory("JAVA", "Java 基础", "CORE"),
                        new PresetCategory("SPRING", "Spring 框架", "CORE"),
                        new PresetCategory("MYSQL", "MySQL", "NORMAL"),
                        new PresetCategory("REDIS", "Redis", "NORMAL"),
                        new PresetCategory("MQ", "消息队列", "NORMAL"),
                        new PresetCategory("DOCKER", "容器化", "NORMAL"),
                        new PresetCategory("PROJECT", "项目经验", "ALWAYS_ONE")
                ),
                new DisplayDTO("☕", "from-orange-400 to-red-500", "bg-orange-100", "text-orange-600"));

        // ===== 前端开发 =====
        register("frontend", "前端开发", "前端开发面试，涵盖 HTML/CSS、JavaScript、React/Vue 等",
                List.of(
                        new PresetCategory("JS", "JavaScript", "CORE"),
                        new PresetCategory("TS", "TypeScript", "CORE"),
                        new PresetCategory("REACT", "React", "CORE"),
                        new PresetCategory("CSS", "HTML/CSS", "NORMAL"),
                        new PresetCategory("VUE", "Vue", "NORMAL"),
                        new PresetCategory("PERF", "性能优化", "NORMAL"),
                        new PresetCategory("PROJECT", "项目经验", "ALWAYS_ONE")
                ),
                new DisplayDTO("⚛️", "from-cyan-400 to-blue-500", "bg-cyan-100", "text-cyan-600"));

        // ===== 数据科学 / AI =====
        register("data-science", "数据科学 / AI", "数据科学与人工智能面试，涵盖机器学习、深度学习、NLP 等",
                List.of(
                        new PresetCategory("ML", "机器学习", "CORE"),
                        new PresetCategory("DL", "深度学习", "CORE"),
                        new PresetCategory("NLP", "自然语言处理", "CORE"),
                        new PresetCategory("CV", "计算机视觉", "NORMAL"),
                        new PresetCategory("SQL", "SQL", "NORMAL"),
                        new PresetCategory("STATS", "统计学", "NORMAL"),
                        new PresetCategory("PROJECT", "项目经验", "ALWAYS_ONE")
                ),
                new DisplayDTO("🤖", "from-purple-400 to-pink-500", "bg-purple-100", "text-purple-600"));

        // ===== DevOps / SRE =====
        register("devops", "DevOps / SRE", "运维开发面试，涵盖 Linux、CI/CD、Kubernetes、监控等",
                List.of(
                        new PresetCategory("LINUX", "Linux", "CORE"),
                        new PresetCategory("K8S", "Kubernetes", "CORE"),
                        new PresetCategory("CICD", "CI/CD", "CORE"),
                        new PresetCategory("MONITOR", "监控体系", "NORMAL"),
                        new PresetCategory("NETWORK", "网络", "NORMAL"),
                        new PresetCategory("SECURITY", "安全", "NORMAL"),
                        new PresetCategory("PROJECT", "项目经验", "ALWAYS_ONE")
                ),
                new DisplayDTO("🐳", "from-teal-400 to-emerald-500", "bg-teal-100", "text-teal-600"));

        // ===== Golang =====
        register("golang", "Go 开发", "Go 后端开发面试，涵盖 Go 语言基础、并发编程、微服务等",
                List.of(
                        new PresetCategory("GO", "Go 语言", "CORE"),
                        new PresetCategory("CONCUR", "并发编程", "CORE"),
                        new PresetCategory("MICRO", "微服务", "CORE"),
                        new PresetCategory("MYSQL", "MySQL", "NORMAL"),
                        new PresetCategory("REDIS", "Redis", "NORMAL"),
                        new PresetCategory("PROJECT", "项目经验", "ALWAYS_ONE")
                ),
                new DisplayDTO("🐹", "from-sky-400 to-indigo-500", "bg-sky-100", "text-sky-600"));

        // ===== Python 后端 =====
        register("python", "Python 后端", "Python 后端开发面试，涵盖 Python 语言、Web 框架、数据库等",
                List.of(
                        new PresetCategory("PYTHON", "Python 语言", "CORE"),
                        new PresetCategory("DJANGO", "Django", "CORE"),
                        new PresetCategory("FLASK", "Flask/FastAPI", "CORE"),
                        new PresetCategory("MYSQL", "MySQL", "NORMAL"),
                        new PresetCategory("REDIS", "Redis", "NORMAL"),
                        new PresetCategory("PROJECT", "项目经验", "ALWAYS_ONE")
                ),
                new DisplayDTO("🐍", "from-green-400 to-emerald-500", "bg-green-100", "text-green-600"));

        log.info("共加载 {} 个预设 Skill", presetRegistry.size());
    }

    private void register(String id, String name, String description,
                          List<PresetCategory> categories, DisplayDTO display) {
        presetRegistry.put(id, new PresetSkill(name, description, categories, display));
    }

    /**
     * 获取所有预设 Skill。
     */
    public List<SkillDTO> getAllSkills() {
        return presetRegistry.entrySet().stream()
                .map(e -> toSkillDTO(e.getKey(), e.getValue()))
                .toList();
    }

    /**
     * 获取单个 Skill。
     *
     * @param skillId 技能标识符
     * @return Skill DTO
     * @throws BusinessException 当 skillId 不存在时抛出
     */
    public SkillDTO getSkill(String skillId) {
        PresetSkill preset = presetRegistry.get(skillId);
        if (preset == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "未找到面试主题: " + skillId);
        }
        return toSkillDTO(skillId, preset);
    }

    /**
     * 通过 AI 解析 JD 文本，提取面试分类。
     * <p>
     * 调用 LLM 分析职位描述，识别出需要考察的技术方向及其分类。
     *
     * @param jdText JD 原文
     * @return 解析出的分类列表
     * @throws BusinessException JD 内容太少或 AI 解析失败时抛出
     */
    public List<CategoryDTO> parseJd(String jdText) {
        if (jdText == null || jdText.length() < 50) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "JD 内容太少（至少 50 字），请补充后重试");
        }

        log.info("开始解析 JD，长度: {}", jdText.length());

        ChatClient chatClient = chatClientBuilder.build();

        String prompt = """
                你是一个专业的技术面试官。请分析以下职位描述（JD），提取出面试时需要考察的技术方向。

                要求：
                - 每个方向返回一个分类对象
                - key 用大写英文缩写（如 JAVA、SPRING、MYSQL、REDIS、DOCKER、K8S）
                - label 使用中文名称
                - priority 根据重要性设置为 CORE（核心）/ NORMAL（常规）/ ALWAYS_ONE（至少一题）
                - 返回格式为 JSON 数组，不要包含 markdown 代码块
                - 示例: [{"key": "JAVA", "label": "Java 基础", "priority": "CORE"}]
                
                职位描述：
                %s
                """.formatted(jdText);

        try {
            String response = chatClient.prompt().user(prompt).call().content();
            if (response == null || response.isBlank()) {
                throw new BusinessException(ErrorCode.AI_SERVICE_ERROR, "AI 未返回解析结果");
            }

            // 解析 JSON
            String json = response.strip()
                    .replaceAll("^```(?:json)?\\s*\\n?", "")
                    .replaceAll("\\n?```$", "")
                    .strip();

            var mapper = new tools.jackson.databind.ObjectMapper();
            var rawCategories = mapper.readValue(json,
                    new tools.jackson.core.type.TypeReference<List<RawCategory>>() {});

            if (rawCategories == null || rawCategories.isEmpty()) {
                throw new BusinessException(ErrorCode.AI_SERVICE_ERROR, "AI 未提取出有效的技术方向");
            }

            return rawCategories.stream()
                    .map(rc -> new CategoryDTO(
                            rc.key() != null ? rc.key().toUpperCase() : "GENERAL",
                            rc.label() != null ? rc.label() : rc.key(),
                            rc.priority() != null ? rc.priority() : "NORMAL",
                            null, null))
                    .toList();

        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("JD 解析失败: {}", e.getMessage(), e);
            throw new BusinessException(ErrorCode.AI_SERVICE_ERROR, "JD 解析失败: " + e.getMessage());
        }
    }

    /**
     * 将内部 PresetSkill 转为 API 返回的 SkillDTO。
     */
    private SkillDTO toSkillDTO(String id, PresetSkill preset) {
        List<CategoryDTO> categories = preset.categories().stream()
                .map(pc -> new CategoryDTO(pc.key(), pc.label(), pc.priority(), null, null))
                .toList();

        return new SkillDTO(id, preset.name(), preset.description(),
                categories, true, null, null, preset.display());
    }

    // ==================== 内部数据结构 ====================

    private record PresetSkill(
            String name,
            String description,
            List<PresetCategory> categories,
            DisplayDTO display
    ) {}

    private record PresetCategory(String key, String label, String priority) {}

    private record RawCategory(String key, String label, String priority) {}
}
