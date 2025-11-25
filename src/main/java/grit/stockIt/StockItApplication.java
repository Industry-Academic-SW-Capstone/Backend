package grit.stockIt;

import grit.stockIt.domain.auth.config.KakaoOAuthProperties;
import grit.stockIt.global.config.FirebaseProperties;
import grit.stockIt.global.config.KisApiProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

import org.springframework.cache.annotation.EnableCaching;

import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableConfigurationProperties({KisApiProperties.class, KakaoOAuthProperties.class, FirebaseProperties.class, grit.stockIt.global.config.GeminiApiProperties.class})
@EnableScheduling
@EnableAsync

@EnableCaching


@SpringBootApplication
public class StockItApplication {

    public static void main(String[] args) {
        SpringApplication.run(StockItApplication.class, args);
    }

}
