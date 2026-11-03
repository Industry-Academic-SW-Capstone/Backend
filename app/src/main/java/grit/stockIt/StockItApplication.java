package grit.stockIt;

import grit.stockIt.domain.auth.config.KakaoOAuthProperties;
import grit.stockIt.global.config.FirebaseProperties;
import grit.stockIt.global.config.KisApiProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

import org.springframework.cache.annotation.EnableCaching;

import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableConfigurationProperties({KisApiProperties.class, KakaoOAuthProperties.class, FirebaseProperties.class, grit.stockIt.global.config.GeminiApiProperties.class})
@EnableScheduling
@EnableAsync

@EnableCaching


// 로그인은 서비스가 직접 확인하고 요청 인증은 JWT 필터가 토큰만으로 한다. 쓰지 않는 기본 메모리 사용자를 만들지 않는다.
@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
public class StockItApplication {

    public static void main(String[] args) {
        SpringApplication.run(StockItApplication.class, args);
    }

}
