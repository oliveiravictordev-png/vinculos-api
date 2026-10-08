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
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.function.LongSupplier;

/**
 * Rate limit em /api: um balde de fichas por IP (barra quem martela a API) e um global (protege a instância).
 * O IP é o do cliente visto pelo proxy reverso (server.forward-headers-strategy), não o X-Forwarded-For enviado
 * pelo próprio cliente, que seria falsificável. Acima do limite: 429 no formato RFC 9457 com Retry-After.
 */
@Component
public class RateLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LogManager.getLogger(RateLimitFilter.class);

    private final RateLimitProperties properties;
    private final LongSupplier clock;
    private final TokenBucket global;
    private final Cache<String, TokenBucket> perIp = Caffeine.newBuilder()
            .maximumSize(100_000)
            .expireAfterAccess(Duration.ofMinutes(10))
            .build();

    @Autowired
    public RateLimitFilter(RateLimitProperties properties) {
        this(properties, System::nanoTime);
    }

    RateLimitFilter(RateLimitProperties properties, LongSupplier clock) {
        this.properties = properties;
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
            reject(response, waitNanos);
            return;
        }
        chain.doFilter(request, response);
    }

    private static void reject(HttpServletResponse response, long waitNanos) throws IOException {
        long retryAfter = Math.max(1, (long) Math.ceil(waitNanos / 1e9));
        response.setStatus(429);
        response.setHeader("Retry-After", Long.toString(retryAfter));
        response.setContentType("application/problem+json");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write("""
                {"type":"about:blank","title":"Too many requests","status":429,\
                "detail":"Request rate limit exceeded, retry in %d s"}""".formatted(retryAfter));
    }
}
