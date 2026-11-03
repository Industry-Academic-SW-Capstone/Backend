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

    @Bean
    public WebClient.Builder webClientBuilder() {
        // Python 서버 등 다른 서버 호출을 위한 WebClient Builder
        // baseUrl은 각 서비스에서 설정
        return WebClient.builder();
    }
}