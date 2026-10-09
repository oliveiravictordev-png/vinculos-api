package com.teste.vinculos.web.security;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Profile;

import java.time.Duration;

/**
 * Configuração da autenticação (variáveis de ambiente, 12-factor).
 *
 * @param jwtPrivateKey        chave RSA privada que assina os tokens (PKCS#8, em Base64 ou PEM). Só a API a tem;
 *                             quem precisa validar um token usa a chave pública publicada em /api/v1/auth/jwks.
 * @param jwtPreviousPublicKey chave pública anterior (X.509, Base64 ou PEM), opcional: durante uma rotação, os
 *                             tokens assinados com a chave antiga continuam valendo até expirar
 * @param ttl                  validade do access token (curta: ele não é consultado no banco a cada requisição)
 * @param refreshTtl           duração máxima da sessão do navegador; o refresh renova o access token até ela
 */
@ConfigurationProperties("app.auth")
@Profile("!seed")
public record AuthProperties(
        String admin1Username,
        String admin1Password,
        String admin2Username,
        String admin2Password,
        String jwtPrivateKey,
        String jwtPreviousPublicKey,
        String issuer,
        Duration ttl,
        Duration refreshTtl) {

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
        if (jwtPrivateKey == null || jwtPrivateKey.isBlank()) {
            throw new IllegalArgumentException("JWT_PRIVATE_KEY must not be blank (generate one with: make jwt-key)");
        }
        if (issuer == null || issuer.isBlank()) {
            throw new IllegalArgumentException("JWT_ISSUER must not be blank");
        }
        if (ttl == null || ttl.isZero() || ttl.isNegative()) {
            throw new IllegalArgumentException("JWT_TTL must be positive");
        }
        if (refreshTtl == null || refreshTtl.compareTo(ttl) < 0) {
            throw new IllegalArgumentException("JWT_REFRESH_TTL must be at least JWT_TTL");
        }
        jwtPreviousPublicKey = jwtPreviousPublicKey == null ? "" : jwtPreviousPublicKey;
    }
}
