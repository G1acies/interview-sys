package interview.guide.modules.llmprovider.dto;

/**
 * Provider 连通性测试结果。
 *
 * @param success 是否测试通过
 * @param message 测试消息（成功时返回模型名，失败时返回错误原因）
 * @param model   返回的模型名称
 */
public record ProviderTestResult(
    boolean success,
    String message,
    String model
) {}
