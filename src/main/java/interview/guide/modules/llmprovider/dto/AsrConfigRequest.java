package interview.guide.modules.llmprovider.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * ASR 配置更新请求。
 */
public record AsrConfigRequest(
    @NotBlank String url,
    @NotBlank String model,
    String apiKey,
    String language,
    String format,
    Integer sampleRate,
    Boolean enableTurnDetection,
    String turnDetectionType,
    Float turnDetectionThreshold,
    Integer turnDetectionSilenceDurationMs
) {}
