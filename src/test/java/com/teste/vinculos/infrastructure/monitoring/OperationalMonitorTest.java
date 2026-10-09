package com.teste.vinculos.infrastructure.monitoring;

import com.github.benmanes.caffeine.cache.Caffeine;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.cache.caffeine.CaffeineCache;
import org.springframework.cache.support.SimpleCacheManager;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class OperationalMonitorTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final CaffeineCache companies = new CaffeineCache("companies", Caffeine.newBuilder().recordStats().build());
    private final OperationalMonitor monitor = monitor();

    @Test
    void firstCheckOnlyTakesTheBaseline() {
        requests(401, 100, 10);

        assertThat(monitor.check()).isEmpty();
    }

    @Test
    void alertsOnStatusBurstsWithinTheWindowNotOnTheRunningTotal() {
        requests(401, 25, 10);
        monitor.check();

        requests(401, 30, 10);
        requests(503, 4, 10);
        assertThat(monitor.check()).containsExactly("http_status status=401 count=30 window=PT1M");

        requests(401, 1, 10);
        assertThat(monitor.check()).isEmpty();
    }

    @Test
    void alertsOnRequestsSlowerThanTheSlo() {
        monitor.check();
        requests(200, 9, 250);
        requests(200, 100, 50);
        assertThat(monitor.check()).isEmpty();

        requests(200, 10, 250);
        assertThat(monitor.check()).containsExactly("slow_requests over_ms=200 count=10 window=PT1M");
    }

    @Test
    void alertsWhenCacheHitRateDropsSharplyAgainstTheRecentAverage() {
        monitor.check();
        cacheTraffic(100, 90);
        assertThat(monitor.check()).isEmpty();

        cacheTraffic(100, 30);
        assertThat(monitor.check()).singleElement().asString().startsWith("cache_hit_drop cache=companies rate=0.30");
    }

    @Test
    void ignoresCacheWindowsWithTooFewRequests() {
        monitor.check();
        cacheTraffic(100, 90);
        monitor.check();

        cacheTraffic(10, 0);
        assertThat(monitor.check()).isEmpty();
    }

    private void requests(int status, int count, long millis) {
        Timer timer = Timer.builder("http.server.requests")
                .tag("status", Integer.toString(status))
                .tag("uri", "/api/v1/customers/companies")
                .serviceLevelObjectives(Duration.ofMillis(OperationalMonitor.SLO_MS))
                .register(registry);
        for (int i = 0; i < count; i++) {
            timer.record(Duration.ofMillis(millis));
        }
    }

    // Cada acerto lê uma chave já gravada; cada erro, uma chave nova.
    private void cacheTraffic(int requests, int hits) {
        companies.put("hot", "value");
        for (int i = 0; i < requests; i++) {
            companies.get(i < hits ? "hot" : "miss-" + System.nanoTime());
        }
    }

    private OperationalMonitor monitor() {
        var caches = new SimpleCacheManager();
        caches.setCaches(List.of(companies));
        caches.afterPropertiesSet();
        var properties = new MonitorProperties(Duration.ofMinutes(1),
                Map.of(401, 30L, 429, 50L, 500, 5L, 503, 5L), 10, 100, 0.3);
        return new OperationalMonitor(registry, caches, properties);
    }
}
