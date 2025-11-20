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

import jakarta.annotation.PostConstruct;
import java.util.TimeZone;

@EnableConfigurationProperties({KisApiProperties.class, KakaoOAuthProperties.class, FirebaseProperties.class})
@EnableScheduling
@EnableAsync

@EnableCaching


@SpringBootApplication
public class StockItApplication {

    @PostConstruct
    public void started() {
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Seoul"));
    }

    public static void main(String[] args) {
        SpringApplication.run(StockItApplication.class, args);
    }

}
