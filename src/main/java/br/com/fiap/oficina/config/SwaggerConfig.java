package br.com.fiap.oficina.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SwaggerConfig {

    static final String ESQUEMA = "bearer-jwt";

    @Bean
    public OpenAPI openAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("Oficina Mecânica - Billing Service")
                        .description("""
                                Orçamento, aprovação pelo cliente, cobrança por QR Code do Mercado Pago (Orders API) e
                                e-mails. Participa da Saga gerando orçamento e cobrança e pedindo reembolso.

                                Autenticação: Bearer JWT de sessão, emitido pela Lambda de login no API Gateway
                                (POST /auth/funcionario, POST /auth/cliente). As permissões vêm da claim roles.
                                Sem token: GET /aprovacao-os (link assinado do e-mail) e POST /webhooks/mercado-pago (assinatura do Mercado Pago).
                                """)
                        .version("4.0.0"))
                .components(new Components().addSecuritySchemes(ESQUEMA, new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP)
                        .scheme("bearer")
                        .bearerFormat("JWT")))
                .addSecurityItem(new SecurityRequirement().addList(ESQUEMA));
    }
}
