package grit.stockIt.global.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

// 부가만 쓰는 외부 설정(FCM).
@Configuration
@EnableConfigurationProperties(FirebaseProperties.class)
public class ExtraConfig {
}
