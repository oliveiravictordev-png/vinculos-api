package com.teste.vinculos.web;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Limites de requisições em /api: por IP do cliente e no total da instância, mais um limite próprio e bem menor
 * para a exportação, que é a requisição mais cara (até milhares de linhas num arquivo).
 *
 * <p>Os baldes ficam na memória de cada instância: com N instâncias atrás do Caddy, o limite efetivo por IP é até
 * N vezes o configurado. Um limite exato entre instâncias pede um armazenamento compartilhado (Redis).
 */
@ConfigurationProperties("app.rate-limit")
public record RateLimitProperties(
        boolean enabled,
        long perIpCapacity,
        double perIpPerSecond,
        long globalCapacity,
        double globalPerSecond,
        long exportCapacity,
        double exportPerMinute) {
}
