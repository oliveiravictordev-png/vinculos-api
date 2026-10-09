package com.teste.vinculos.web.security;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.teste.vinculos.domain.SessionStore;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.context.annotation.Profile;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/**
 * Valida, a cada requisição, que a sessão do token não foi revogada. O resultado fica 30 s em cache por instância:
 * sem isso cada requisição faria uma ida ao banco; com isso, um logout vale na própria instância na hora (o cache é
 * limpo) e nas demais em até 30 s.
 *
 * <p>Se o banco falhar, a sessão é recusada (falha fechada): sem banco a API também não responderia a consulta.
 */
@Component
@Profile("!seed")
public class ActiveSessions implements OAuth2TokenValidator<Jwt> {

    static final Duration CACHE_TTL = Duration.ofSeconds(30);

    private static final Logger log = LogManager.getLogger(ActiveSessions.class);
    private static final OAuth2Error REVOKED = new OAuth2Error("invalid_token", "Session is no longer active", null);

    private final SessionStore store;
    private final Cache<String, Boolean> active = Caffeine.newBuilder()
            .expireAfterWrite(CACHE_TTL)
            .maximumSize(10_000)
            .build();

    public ActiveSessions(SessionStore store) {
        this.store = store;
    }

    String create(String username, Instant expiresAt) {
        return store.create(username, expiresAt);
    }

    boolean isActive(String sessionId, String username) {
        if (sessionId == null || username == null) {
            return false;
        }
        return active.get(sessionId + "|" + username, key -> store.isActive(sessionId, username));
    }

    void revoke(String sessionId, String username) {
        store.revoke(sessionId);
        active.invalidate(sessionId + "|" + username);
    }

    @Override
    public OAuth2TokenValidatorResult validate(Jwt jwt) {
        try {
            return isActive(jwt.getClaimAsString(TokenService.SESSION_ID), jwt.getSubject())
                    ? OAuth2TokenValidatorResult.success()
                    : OAuth2TokenValidatorResult.failure(REVOKED);
        } catch (RuntimeException e) {
            log.error("Session check failed", e);
            return OAuth2TokenValidatorResult.failure(REVOKED);
        }
    }
}
