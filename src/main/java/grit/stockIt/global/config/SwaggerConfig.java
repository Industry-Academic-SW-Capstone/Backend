package grit.stockIt.global.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springdoc.core.models.GroupedOpenApi;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;

import java.util.List;
import java.util.Set;

@Configuration
public class SwaggerConfig {

    private static final String SCHEME_NAME = "bearerAuth";

    @Bean
    public GroupedOpenApi allApis() {
        return GroupedOpenApi.builder()
                .group("all") // 모두 적용
                .pathsToMatch("/**")
                .build();
    }

    @Bean
    public OpenAPI openAPI() {
        return new OpenAPI()
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
    public OpenApiCustomizer securityOpenApiCustomiser() {
        Set<String> whitelist = Set.of(
                "/api/auth/kakao/callback",
                "/api/auth/kakao/signup",
                "/api/auth/login",
                "/api/auth/signup",
                "/api/auth/refresh"
        );

        return openApi -> openApi.getPaths().forEach((path, item) -> {
            if (whitelist.contains(path)) {
                item.readOperations().forEach(op -> op.setSecurity(List.of()));
            }
        });
    }

    @Bean
    public OpenAPI customOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("Spring API 문서")
                        .version("v1.0")
                        .description("Spring Boot 기반 API 문서 입니다.")
                        .license(new License().name("Apache 2.0").url("http://springdoc.org")));
    }
}