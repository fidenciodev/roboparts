package br.com.roboparts.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.PathItem;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {
    @Bean
    OpenAPI roboPartsOpenApi() {
        return new OpenAPI().info(new Info().title("RoboParts API").version("0.3.0")
                .description("Robôs, componentes, checklists com snapshot, movimentações, auditoria e autenticação por sessão. Antes de um POST, obtenha o token em "
                        + "/api/auth/csrf e envie o cabeçalho X-CSRF-TOKEN. Depois de login ou logout, renove o token."))
                .components(new Components().addSecuritySchemes("employeeSession", new SecurityScheme()
                        .type(SecurityScheme.Type.APIKEY).in(SecurityScheme.In.COOKIE).name("JSESSIONID"))
                        .addSecuritySchemes("csrfToken", new SecurityScheme().type(SecurityScheme.Type.APIKEY)
                                .in(SecurityScheme.In.HEADER).name("X-CSRF-TOKEN")));
    }
    @Bean
    OpenApiCustomizer sessionAndCsrfRequirements() {
        return api -> api.getPaths().forEach((path,item) -> item.readOperationsMap().forEach((method,operation) -> {
            if (!path.startsWith("/api/") || path.equals("/api/auth/csrf")) return;
            SecurityRequirement required = new SecurityRequirement();
            if (!path.equals("/api/auth/login") && !path.equals("/api/auth/register")) required.addList("employeeSession");
            if (method == PathItem.HttpMethod.POST) required.addList("csrfToken");
            if (!required.isEmpty()) operation.addSecurityItem(required);
        }));
    }
}
