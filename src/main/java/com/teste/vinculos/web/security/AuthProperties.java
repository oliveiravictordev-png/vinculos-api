package com.teste.vinculos.web.security;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Profile;

import java.time.Duration;

@ConfigurationProperties("app.auth")
@Profile("!seed")
public record AuthProperties(
        String admin1Username,
        String admin1Password,
        String admin2Username,
        String admin2Password,
        String jwtSecret,
        String issuer,
        Duration ttl) {

    public AuthProperties {
        if (admin1Username == null || admin1Username.isBlank()) {
            throw new IllegalArgumentException("AUTH_ADMIN1_USERNAME must not be blank");
        }
        if (admin1Password == null || admin1Password.isBlank()) {
            throw new IllegalArgumentException("AUTH_ADMIN1_PASSWORD must not be blank");
        }
        if (admin2Username == null || admin2Username.isBlank()) {
            throw new IllegalArgumentException("AUTH_ADMIN2_USERNAME must not be blank");
        }
        if (admin2Password == null || admin2Password.isBlank()) {
            throw new IllegalArgumentException("AUTH_ADMIN2_PASSWORD must not be blank");
        }
        if (admin1Username.equals(admin2Username)) {
            throw new IllegalArgumentException("Administrator usernames must be different");
        }
        if (jwtSecret == null || jwtSecret.getBytes(java.nio.charset.StandardCharsets.UTF_8).length < 32) {
            throw new IllegalArgumentException("JWT_SECRET must contain at least 32 bytes");
        }
        if (issuer == null || issuer.isBlank()) {
            throw new IllegalArgumentException("JWT_ISSUER must not be blank");
        }
        if (ttl == null || ttl.isZero() || ttl.isNegative()) {
            throw new IllegalArgumentException("JWT_TTL must be positive");
        }
    }
}
