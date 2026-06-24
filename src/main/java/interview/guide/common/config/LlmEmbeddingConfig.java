package interview.guide.common.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * EmbeddingModel 配置。
 * <p>
 * 在 {@code spring.ai.openai.*} 自动配置的 EmbeddingModel 基础上，
 * 显式声明 Bean 以便 PgVectorStore 能正确注入。
 * Spring AI 的 openai starter 会自动创建 OpenAiEmbeddingModel，
 * 这里仅做日志确认，确保向量化功能启动时感知到模型就绪。
 */
@Configuration
@Slf4j
public class LlmEmbeddingConfig {

    @Bean
    public EmbeddingModel embeddingModel(EmbeddingModel autoConfiguredModel) {
        log.info("EmbeddingModel bean initialized: {}", autoConfiguredModel.getClass().getName());
        return autoConfiguredModel;
    }
}
