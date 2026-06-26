package interview.guide.modules.llmprovider.dto;

/**
 * 更新 Provider 的请求 DTO。
 * <p>
 * 所有字段均可选（null 表示不更新）。
 * 注意：apiKey 传 null 表示不修改密钥，传空字符串表示清空。
 *
 * @param baseUrl             API 基础地址
 * @param apiKey              新的 API Key（null=不修改，""=清空）
 * @param model               聊天模型名称
 * @param embeddingModel      向量化模型名称
 * @param embeddingDimensions 向量维度
 * @param supportsEmbedding   是否支持向量化
 * @param temperature         温度参数
 */
public record UpdateProviderRequest(
    String baseUrl,
    String apiKey,
    String model,
    String embeddingModel,
    Integer embeddingDimensions,
    Boolean supportsEmbedding,
    Double temperature
) {
    public UpdateProviderRequest(String baseUrl, String apiKey, String model,
                                  String embeddingModel, Double temperature) {
        this(baseUrl, apiKey, model, embeddingModel, null, null, temperature);
    }
}
