package grit.stockIt.global.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

@Configuration
public class WebClientConfig {

    @Value("${kis.api.url}")
    private String kisApiUrl;

    @Bean
    public WebClient webClient() {
        // KIS API 기본 URL을 설정한 WebClient 빌더 사용
        return WebClient.builder()
                .baseUrl(kisApiUrl)
                .build();
    }
}