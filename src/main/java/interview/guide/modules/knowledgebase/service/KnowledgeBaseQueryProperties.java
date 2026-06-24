package interview.guide.modules.knowledgebase.service;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 知识库 RAG 查询配置属性。
 * <p>
 * 从 {@code application.yml} 的 {@code app.ai.rag.*} 读取。
 * 控制查询改写、检索参数和历史上下文等行为。
 * <p>
 * <b>配置项说明：</b>
 * <ul>
 *   <li>{@code rewrite.enabled} — 是否启用 Query Rewrite（LLM 改写问题以提升检索效果）</li>
 *   <li>{@code search.shortQueryLength} — 短查询的字符数阈值（≤此值使用 short 参数）</li>
 *   <li>{@code search.topkShort/Medium/Long} — 不同长度查询的检索数量</li>
 *   <li>{@code search.minScoreShort/Default} — 相似度最低阈值</li>
 *   <li>{@code history.enabled} — 是否启用多轮会话历史上下文</li>
 *   <li>{@code history.maxMessages} — 加载历史消息的最大条数</li>
 * </ul>
 */
@Data
@Component
@ConfigurationProperties(prefix = "app.ai.rag")
public class KnowledgeBaseQueryProperties {

    private Rewrite rewrite = new Rewrite();
    private Search search = new Search();
    private History history = new History();

    /** Prompt 模板路径。指向 classpath 下的 .st 文件。 */
    private String systemPromptPath = "classpath:prompts/knowledgebase-query-system.st";
    private String userPromptPath = "classpath:prompts/knowledgebase-query-user.st";
    private String rewritePromptPath = "classpath:prompts/knowledgebase-query-rewrite.st";

    @Data
    public static class Rewrite {
        private boolean enabled = true;
    }

    @Data
    public static class Search {
        private int shortQueryLength = 4;
        private int topkShort = 20;
        private int topkMedium = 12;
        private int topkLong = 8;
        private double minScoreShort = 0.25;
        private double minScoreDefault = 0.28;
    }

    @Data
    public static class History {
        private boolean enabled = true;
        private int maxMessages = 10;
    }
}
