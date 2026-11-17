package grit.stockIt.global.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Gemini API 설정 Properties
 */
@ConfigurationProperties(prefix = "gemini.api")
public record GeminiApiProperties(
        String apiKey,
        String modelName,
        String baseUrl
) {}

