package grit.stockIt.global.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

// 어느 실행 클래스(app 또는 서비스별)로 띄워도 같은 기능이 켜지도록 실행 클래스 대신 모듈에 둔다.
// KIS 설정은 시세(웹소켓)와 증권사(REST)가 함께 쓴다.
@Configuration
@EnableScheduling
@EnableAsync
@EnableConfigurationProperties(KisApiProperties.class)
public class CommonConfig {
}
