package interview.guide.modules.llmprovider.dto;

/**
 * 默认 Provider 配置 DTO。
 *
 * @param defaultProvider          默认聊天 Provider 的 ID
 * @param defaultEmbeddingProvider 默认向量化 Provider 的 ID
 */
public record DefaultProviderDTO(
    String defaultProvider,
    String defaultEmbeddingProvider
) {
    public DefaultProviderDTO(String defaultProvider) {
        this(defaultProvider, null);
    }
}
