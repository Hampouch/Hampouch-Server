package Hampouch.server.global.openapi;

import Hampouch.server.global.security.SecurityConfig;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.AntPathMatcher;

@Configuration
@SecurityScheme(
        name = OpenApiConfig.BEARER_AUTH_SCHEME,
        type = SecuritySchemeType.HTTP,
        scheme = "bearer",
        bearerFormat = "JWT"
)
public class OpenApiConfig {

    static final String BEARER_AUTH_SCHEME = "bearerAuth";

    private static final AntPathMatcher PATH_MATCHER = new AntPathMatcher();

    @Bean
    OpenApiCustomizer bearerAuthRequirementCustomizer() {
        return openApi -> openApi.getPaths().forEach((path, pathItem) -> {
            boolean permitAll = SecurityConfig.PERMIT_ALL_REQUEST_PATTERNS.stream()
                    .anyMatch(pattern -> PATH_MATCHER.match(pattern, path));

            if (!permitAll) {
                pathItem.readOperations().forEach(operation -> operation.addSecurityItem(
                        new SecurityRequirement().addList(BEARER_AUTH_SCHEME)
                ));
            }
        });
    }
}
