package grit.stockIt.global.config;

import grit.stockIt.domain.auth.config.KakaoOAuthProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

// 증권사만 쓰는 외부 API 설정(카카오 로그인, Gemini).
@Configuration
@EnableConfigurationProperties({KakaoOAuthProperties.class, GeminiApiProperties.class})
public class BrokerConfig {
}
