package io.aeyer.anchor.server.api;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI 3 metadata + the Bearer security scheme that drives the "Authorize"
 * button in /swagger-ui. Springdoc auto-discovers the controllers and DTOs;
 * this bean only fills in the project-level info and the auth contract so the
 * generated spec is truthful about ANCHOR_API_TOKEN.
 */
@Configuration
public class OpenApiConfig {

    private static final String SECURITY_SCHEME_NAME = "AnchorApiToken";

    @Bean
    public OpenAPI anchorOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("Anchor")
                        .version("v0")
                        .description("""
                                Source-grounded chunk validation. Two interfaces over one hierarchy:
                                machine-facing `/validate` for branchable JSON judgment, and human-facing
                                `/documents/{id}/ask` for token-streamed three-agent deliberation.
                                See SPEC.md for the full design.""")
                        .license(new License().name("Apache-2.0").url("https://www.apache.org/licenses/LICENSE-2.0")))
                .addSecurityItem(new SecurityRequirement().addList(SECURITY_SCHEME_NAME))
                .components(new Components().addSecuritySchemes(SECURITY_SCHEME_NAME,
                        new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("opaque")
                                .description("Server enforces this only when ANCHOR_API_TOKEN is set; "
                                        + "otherwise the dev workflow runs without a token.")));
    }
}
