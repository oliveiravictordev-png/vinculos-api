package com.teste.vinculos.web;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Metadados da documentação OpenAPI (Swagger UI em /swagger-ui.html, especificação em /v3/api-docs). */
@Configuration(proxyBeanMethods = false)
public class OpenApiConfig {

    @Bean
    OpenAPI vinculosOpenApi() {
        return new OpenAPI().info(new Info()
                .title("Vinculos API")
                .version("1.0.0")
                .description("""
                        Consulta de vínculos cliente × empresa sobre MongoDB, dimensionada para 1 bilhão de registros.

                        Chave do cliente: ano + tipo do documento + documento. Os exemplos usam a chave do enunciado \
                        (2026 / CPF / 056.858.627-17). Health check: /actuator/health (liveness e readiness em \
                        /actuator/health/liveness e /actuator/health/readiness)."""));
    }
}
