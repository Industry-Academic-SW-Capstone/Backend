package grit.stockIt.global.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

@Configuration
public class WebClientConfig {

    @Bean
    public WebClient webClient() {
        // WebClient의 기본 빌더를 사용해 Bean으로 등록
        return WebClient.builder().build();
    }
}