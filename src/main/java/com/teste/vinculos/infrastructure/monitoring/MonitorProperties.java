package com.teste.vinculos.infrastructure.monitoring;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.Map;

/**
 * Limites dos alertas da aplicação, sempre por janela ({@code interval}) e por instância.
 *
 * @param statusThresholds respostas com o status (401, 429, 500, 503...) por janela que disparam alerta
 * @param maxSlowRequests  requisições acima do SLO de 200 ms por janela
 * @param minCacheRequests acessos mínimos ao cache na janela para avaliar a taxa de acerto
 * @param cacheHitDrop     queda da taxa de acerto (0 a 1) em relação à média das janelas anteriores
 */
@ConfigurationProperties("app.monitor")
public record MonitorProperties(Duration interval, Map<Integer, Long> statusThresholds, long maxSlowRequests,
                                long minCacheRequests, double cacheHitDrop) {

    public MonitorProperties {
        statusThresholds = statusThresholds == null ? Map.of() : Map.copyOf(statusThresholds);
    }
}
