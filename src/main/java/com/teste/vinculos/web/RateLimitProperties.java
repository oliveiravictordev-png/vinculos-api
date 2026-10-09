package com.teste.vinculos.web;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Limites de requisições em /api: por IP do cliente e no total da instância, mais limites próprios e bem menores
 * para as requisições mais caras ou mais visadas: a exportação (até milhares de linhas num arquivo) e o login (alvo
 * de tentativa e erro de senha; o bloqueio por usuário fica no LoginThrottle).
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
        double exportPerMinute,
        long loginCapacity,
        double loginPerMinute) {
}
