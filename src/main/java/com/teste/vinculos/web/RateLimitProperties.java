package com.teste.vinculos.web;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Limites de requisições em /api: por IP do cliente e no total da instância. */
@ConfigurationProperties("app.rate-limit")
public record RateLimitProperties(
        boolean enabled,
        long perIpCapacity,
        double perIpPerSecond,
        long globalCapacity,
        double globalPerSecond) {
}
