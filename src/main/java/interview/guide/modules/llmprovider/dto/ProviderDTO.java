package interview.guide.modules.llmprovider.dto;

/**
 * Provider 列表项/详情 DTO（前端展示用）。
 * <p>
 * apiKey 以脱敏形式返回（如 "sk-****a42"），不可逆。
 *
 * @param id                       Provider 标识
 * @param baseUrl                  API 基础地址
 * @param maskedApiKey             脱敏后的 API Key
 * @param model                    聊天模型名称
 * @param embeddingModel           向量化模型名称
 * @param embeddingDimensions      向量维度
 * @param supportsEmbedding        是否支持向量化
 * @param temperature              温度参数
 * @param defaultChatProvider      是否为默认聊天 Provider
 * @param defaultEmbeddingProvider 是否为默认向量化 Provider
 */
public record ProviderDTO(
    String id,
    String baseUrl,
    String maskedApiKey,
    String model,
    String embeddingModel,
    Integer embeddingDimensions,
    boolean supportsEmbedding,
    Double temperature,
    boolean defaultChatProvider,
    boolean defaultEmbeddingProvider
) {}
