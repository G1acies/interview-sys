package interview.guide.modules.llmprovider.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * LLM Provider 实体。
 * <p>
 * 对应数据库 {@code llm_providers} 表。
 * 存储每一个 LLM 服务商的连接配置（baseUrl、apiKey、model 等）。
 * apiKey 以加密形式存储，查询时返回脱敏版。
 */
@Entity
@Table(name = "llm_providers", indexes = {
    @Index(name = "idx_provider_id", columnList = "providerId", unique = true)
})
@Getter
@Setter
@NoArgsConstructor
public class LlmProviderEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Provider 的唯一标识，如 "dashscope"、"deepseek"。不可变。 */
    @Column(nullable = false, unique = true, length = 50)
    private String providerId;

    /** API 基础地址，如 "https://dashscope.aliyuncs.com/compatible-mode/v1"。 */
    @Column(nullable = false, length = 500)
    private String baseUrl;

    /** 加密后的 API Key。查询时返回脱敏版（如 "sk-****a42"）。 */
    @Column(nullable = false, length = 500)
    private String encryptedApiKey;

    /** 聊天模型名称，如 "qwen3.5-flash"。 */
    @Column(nullable = false)
    private String model;

    /** 向量化模型名称，如 "text-embedding-v3"。null 表示不支持向量化。 */
    private String embeddingModel;

    /** 向量维度（如 1024）。null 时使用全局默认值。 */
    private Integer embeddingDimensions;

    /** 是否支持向量化。false 时该 Provider 只能用于聊天。 */
    private Boolean supportsEmbedding = false;

    /** 模型温度参数 0~2。null 表示使用 Provider 默认值。 */
    private Double temperature;
}
