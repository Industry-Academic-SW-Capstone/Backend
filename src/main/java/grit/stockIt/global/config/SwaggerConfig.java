package grit.stockIt.global.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import org.springdoc.core.customizers.GlobalOpenApiCustomizer;
import org.springdoc.core.models.GroupedOpenApi;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;

import java.util.Collections;
import java.util.List;
import java.util.Set;

@Configuration
public class SwaggerConfig {

    private static final String SCHEME_NAME = "bearerAuth";

    @Bean
    public GroupedOpenApi allApis() {
        return GroupedOpenApi.builder()
                .group("all")
                .pathsToMatch("/**")
                .build();
    }

    @Bean
    public OpenAPI customOpenAPI() {
        return new OpenAPI()
                .servers(List.of(
                        new Server().url("/").description("현재 서버 (상대 경로)"),
                        new Server().url("http://www.stockit.live").description("StockIt 배포 서버"),
                        new Server().url("http://localhost:8080").description("StockIt 로컬 서버")
                ))
                .info(new Info()
                        .title("StockIt API")
                        .version("v1.0")
                        .description("주식 모의투자 서비스 API")
                        .license(new License().name("Apache 2.0").url("http://springdoc.org")))
                .components(new Components().addSecuritySchemes(
                        SCHEME_NAME,
                        new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")
                                .in(SecurityScheme.In.HEADER)
                                .name(HttpHeaders.AUTHORIZATION)))
                .addSecurityItem(new SecurityRequirement().addList(SCHEME_NAME));
    }

    @Bean
    public GlobalOpenApiCustomizer securityOpenApiCustomizer() {
        return openApi -> {
            Set<String> whitelist = Set.of(
                    "/api/auth/kakao/callback",
                    "/api/auth/kakao/signup/complete",
                    "/api/members/login",
                    "/api/members/signup"
            );

            if (openApi.getPaths() != null) {
                openApi.getPaths().forEach((path, item) -> {
                    if (whitelist.contains(path)) {
                        item.readOperations().forEach(op -> op.setSecurity(Collections.emptyList()));
                    }
                });
            }
        };
    }
}