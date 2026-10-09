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
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerExceptionResolver;

import java.io.IOException;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.LongSupplier;

/**
 * Rate limit em /api: um balde de fichas por IP (barra quem martela a API) e um global (protege a instância). A
 * exportação e o login consomem também de baldes próprios por IP, bem menores.
 * O IP é o do cliente visto pelo proxy reverso (server.forward-headers-strategy), não o X-Forwarded-For enviado
 * pelo próprio cliente, que seria falsificável. Acima do limite, a resposta 429 é montada pelo
 * {@link ApiExceptionHandler}, no mesmo formato dos demais erros.
 */
@Component
@Profile("!seed")
public class RateLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LogManager.getLogger(RateLimitFilter.class);

    private final RateLimitProperties properties;
    private final HandlerExceptionResolver exceptionResolver;
    private final LongSupplier clock;
    private final TokenBucket global;
    static final String EXPORT_PATH = "/api/v1/customers/export";
    static final Set<String> LOGIN_PATHS = Set.of("/api/v1/auth/token", "/api/v1/auth/session");

    private final Cache<String, TokenBucket> perIp = buckets();
    // Baldes por IP mais apertados para caminhos específicos; os dois logins dividem o mesmo balde.
    private final Map<String, PathLimit> pathLimits;

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
        var export = new PathLimit(properties.exportCapacity(), properties.exportPerMinute() / 60, buckets());
        var login = new PathLimit(properties.loginCapacity(), properties.loginPerMinute() / 60, buckets());
        var limits = new HashMap<String, PathLimit>();
        limits.put(EXPORT_PATH, export);
        LOGIN_PATHS.forEach(path -> limits.put(path, login));
        this.pathLimits = Map.copyOf(limits);
    }

    private record PathLimit(long capacity, double perSecond, Cache<String, TokenBucket> buckets) {

        long tryConsume(String ip, long now) {
            return buckets.get(ip, key -> new TokenBucket(capacity, perSecond, now)).tryConsume(now);
        }
    }

    private static Cache<String, TokenBucket> buckets() {
        return Caffeine.newBuilder().maximumSize(100_000).expireAfterAccess(Duration.ofMinutes(10)).build();
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
        PathLimit pathLimit = pathLimits.get(request.getRequestURI());
        long waitNanos = pathLimit == null ? 0 : pathLimit.tryConsume(ip, now);
        if (waitNanos == 0) {
            waitNanos = perIp.get(ip, key -> new TokenBucket(properties.perIpCapacity(), properties.perIpPerSecond(), now))
                    .tryConsume(now);
        }
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
