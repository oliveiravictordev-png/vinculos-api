package com.teste.vinculos.support;

import com.teste.vinculos.application.AuditQueriesUseCase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.cache.CacheManager;
import org.springframework.cache.support.NoOpCacheManager;
import org.springframework.context.annotation.Bean;

import java.time.Clock;

/**
 * Beans que os testes de fatia web ({@code @WebMvcTest}) precisam e que vêm de fora do pacote web: cache desligado
 * (a aplicação usa @EnableCaching), relógio e auditoria real sobre uma trilha em memória.
 */
@TestConfiguration
public class WebSliceConfig {

    @Bean
    CacheManager cacheManager() {
        return new NoOpCacheManager();
    }

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    InMemoryQueryAuditLog queryAuditLog() {
        return new InMemoryQueryAuditLog();
    }

    @Bean
    AuditQueriesUseCase auditQueriesUseCase(InMemoryQueryAuditLog log, Clock clock) {
        return new AuditQueriesUseCase(log, clock);
    }
}
