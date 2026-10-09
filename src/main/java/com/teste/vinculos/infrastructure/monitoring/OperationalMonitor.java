package com.teste.vinculos.infrastructure.monitoring;

import com.github.benmanes.caffeine.cache.Cache;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.distribution.CountAtBucket;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.cache.CacheManager;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Alertas do que só a aplicação enxerga: muitos 401/429/500/503, requisições acima de 200 ms e queda brusca da
 * taxa de acerto do cache. A cada janela compara as métricas do Micrometer com as da janela anterior (os
 * contadores são acumulados desde a subida, então o que importa é a diferença) e loga cada alerta em ERROR com o
 * prefixo {@value #PREFIX}. O agente OpenTelemetry leva esses logs ao Elastic, onde uma regra transforma cada um
 * em notificação (consulta no README).
 *
 * <p>O que precisa ser visto de fora da aplicação (API fora do ar, MongoDB sem primário, replica set atrasado,
 * disco) fica no {@code deploy/monitor.sh}, que roda no host: se a API cair, este monitor cai junto.
 */
@Component
@Profile("!seed")
public class OperationalMonitor {

    static final String PREFIX = "ALERT";
    static final long SLO_MS = 200;

    private static final Logger log = LogManager.getLogger(OperationalMonitor.class);
    private static final String HTTP_REQUESTS = "http.server.requests";
    // Peso da janela atual na média móvel da taxa de acerto: a média reflete os últimos ~5 minutos.
    private static final double BASELINE_WEIGHT = 0.2;

    private final MeterRegistry registry;
    private final CacheManager caches;
    private final MonitorProperties properties;
    private final Map<String, Double> cacheBaseline = new HashMap<>();
    private Snapshot previous;

    public OperationalMonitor(MeterRegistry registry, CacheManager caches, MonitorProperties properties) {
        this.registry = registry;
        this.caches = caches;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${app.monitor.interval}", initialDelayString = "${app.monitor.interval}")
    public void run() {
        check().forEach(alert -> log.error("{} {}", PREFIX, alert));
    }

    /** Fecha a janela atual e devolve os alertas dela (vazio na primeira chamada, que só tira a foto inicial). */
    synchronized List<String> check() {
        Snapshot current = snapshot();
        List<String> alerts = previous == null ? List.of() : compare(previous, current);
        log.debug("Monitor window closed: {} alert(s), statuses {}", alerts.size(), current.byStatus());
        previous = current;
        return alerts;
    }

    private List<String> compare(Snapshot before, Snapshot now) {
        var alerts = new ArrayList<String>();
        properties.statusThresholds().forEach((status, threshold) -> {
            long count = now.byStatus().getOrDefault(status, 0L) - before.byStatus().getOrDefault(status, 0L);
            if (count >= threshold) {
                alerts.add("http_status status=%d count=%d window=%s".formatted(status, count, properties.interval()));
            }
        });
        long slow = now.slowRequests() - before.slowRequests();
        if (slow >= properties.maxSlowRequests()) {
            alerts.add("slow_requests over_ms=%d count=%d window=%s".formatted(SLO_MS, slow, properties.interval()));
        }
        now.caches().forEach((name, stats) -> {
            CacheStats old = before.caches().getOrDefault(name, new CacheStats(0, 0));
            long requests = stats.requests() - old.requests();
            if (requests < properties.minCacheRequests()) {
                return;
            }
            double rate = (double) (stats.hits() - old.hits()) / requests;
            Double baseline = cacheBaseline.get(name);
            if (baseline != null && baseline - rate >= properties.cacheHitDrop()) {
                alerts.add(String.format(Locale.ROOT, "cache_hit_drop cache=%s rate=%.2f baseline=%.2f requests=%d",
                        name, rate, baseline, requests));
            }
            cacheBaseline.put(name, baseline == null ? rate : baseline + BASELINE_WEIGHT * (rate - baseline));
        });
        return alerts;
    }

    private Snapshot snapshot() {
        var byStatus = new HashMap<Integer, Long>();
        long slow = 0;
        for (Timer timer : registry.find(HTTP_REQUESTS).timers()) {
            String status = timer.getId().getTag("status");
            if (status != null && status.chars().allMatch(Character::isDigit)) {
                byStatus.merge(Integer.parseInt(status), timer.count(), Long::sum);
            }
            slow += timer.count() - withinSlo(timer);
        }
        var cacheStats = new HashMap<String, CacheStats>();
        for (String name : caches.getCacheNames()) {
            var cache = caches.getCache(name);
            if (cache != null && cache.getNativeCache() instanceof Cache<?, ?> caffeine) {
                var stats = caffeine.stats();
                cacheStats.put(name, new CacheStats(stats.hitCount(), stats.requestCount()));
            }
        }
        return new Snapshot(Map.copyOf(byStatus), slow, Map.copyOf(cacheStats));
    }

    // Sem o bucket do SLO (configuração ausente), considera tudo dentro: melhor nenhum alerta que um falso.
    private static long withinSlo(Timer timer) {
        for (CountAtBucket bucket : timer.takeSnapshot().histogramCounts()) {
            if (bucket.bucket(TimeUnit.MILLISECONDS) == SLO_MS) {
                return (long) bucket.count();
            }
        }
        return timer.count();
    }

    private record Snapshot(Map<Integer, Long> byStatus, long slowRequests, Map<String, CacheStats> caches) {
    }

    private record CacheStats(long hits, long requests) {
    }
}
