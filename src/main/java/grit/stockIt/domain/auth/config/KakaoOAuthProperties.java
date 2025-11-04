package grit.stockIt.domain.auth.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties(prefix = "kakao")
public class KakaoOAuthProperties {
    private String restApiKey;
    private String redirectUri;
    private String adminKey;
}