package grit.stockIt.domain.auth.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Primary;

@Getter
@Setter

@ConfigurationProperties(prefix = "kakao")
@Primary
public class KakaoOAuthProperties {
    private String restApiKey;
    private String redirectUri;
}