package grit.stockIt.global.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "kis.api")
public record KisApiProperties(
        String url,
        String appkey,
        String appsecret
) {}