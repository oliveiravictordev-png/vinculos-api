package com.teste.vinculos.web;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerExceptionResolver;

import java.io.IOException;
import java.time.Duration;
import java.util.function.LongSupplier;

/**
 * Rate limit em /api: um balde de fichas por IP (barra quem martela a API) e um global (protege a instância).
 * O IP é o do cliente visto pelo proxy reverso (server.forward-headers-strategy), não o X-Forwarded-For enviado
 * pelo próprio cliente, que seria falsificável. Acima do limite, a resposta 429 é montada pelo
 * {@link ApiExceptionHandler}, no mesmo formato dos demais erros.
 */
@Component
public class RateLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LogManager.getLogger(RateLimitFilter.class);

    private final RateLimitProperties properties;
    private final HandlerExceptionResolver exceptionResolver;
    private final LongSupplier clock;
    private final TokenBucket global;
    private final Cache<String, TokenBucket> perIp = Caffeine.newBuilder()
            .maximumSize(100_000)
            .expireAfterAccess(Duration.ofMinutes(10))
            .build();

    @Autowired
    public RateLimitFilter(RateLimitProperties properties,
                           @Qualifier("handlerExceptionResolver") HandlerExceptionResolver exceptionResolver) {
        this(properties, exceptionResolver, System::nanoTime);
    }

    RateLimitFilter(RateLimitProperties properties, HandlerExceptionResolver exceptionResolver, LongSupplier clock) {
        this.properties = properties;
        this.exceptionResolver = exceptionResolver;
        this.clock = clock;
        this.global = new TokenBucket(properties.globalCapacity(), properties.globalPerSecond(), clock.getAsLong());
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !properties.enabled() || !request.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        long now = clock.getAsLong();
        String ip = request.getRemoteAddr();
        long waitNanos = perIp.get(ip, key -> new TokenBucket(properties.perIpCapacity(), properties.perIpPerSecond(), now))
                .tryConsume(now);
        if (waitNanos == 0) {
            waitNanos = global.tryConsume(now);
        }
        if (waitNanos > 0) {
            log.debug("Rate limit exceeded for {} on {}", ip, request.getRequestURI());
            long retryAfterSeconds = Math.max(1, (long) Math.ceil(waitNanos / 1e9));
            exceptionResolver.resolveException(request, response, null, new RateLimitExceededException(retryAfterSeconds));
            return;
        }
        chain.doFilter(request, response);
    }
}
