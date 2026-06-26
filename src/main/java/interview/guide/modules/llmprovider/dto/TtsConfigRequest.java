package interview.guide.modules.llmprovider.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * TTS 配置更新请求。
 */
public record TtsConfigRequest(
    @NotBlank String model,
    String apiKey,
    String voice,
    String format,
    Integer sampleRate,
    String mode,
    String languageType,
    Float speechRate,
    Integer volume
) {}
