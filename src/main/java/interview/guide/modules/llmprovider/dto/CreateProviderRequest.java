package interview.guide.modules.llmprovider.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 创建 Provider 的请求 DTO。
 *
 * @param id                  Provider 唯一标识，如 "dashscope"
 * @param baseUrl             API 基础地址
 * @param apiKey              API Key
 * @param model               聊天模型名称
 * @param embeddingModel      向量化模型名称（可选）
 * @param embeddingDimensions 向量维度（可选）
 * @param supportsEmbedding   是否支持向量化
 * @param temperature         温度参数（可选）
 */
public record CreateProviderRequest(
    @NotBlank String id,
    @NotBlank String baseUrl,
    @NotBlank String apiKey,
    @NotBlank String model,
    String embeddingModel,
    Integer embeddingDimensions,
    Boolean supportsEmbedding,
    Double temperature
) {
    public CreateProviderRequest(String id, String baseUrl, String apiKey, String model,
                                  String embeddingModel, Double temperature) {
        this(id, baseUrl, apiKey, model, embeddingModel, null, null, temperature);
    }
}
