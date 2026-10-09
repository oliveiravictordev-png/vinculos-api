package com.teste.vinculos.infrastructure.mongo;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Profile;

import java.time.Duration;

/**
 * Configuração da trilha de auditoria.
 *
 * @param hashSecret chave do HMAC do documento. Um SHA-256 simples de CPF se reverte por força bruta (são só 10⁹
 *                   CPFs); com a chave secreta, o hash só serve para quem a tem.
 * @param retention  por quanto tempo os registros ficam guardados (LGPD: só pelo tempo necessário); o MongoDB
 *                   apaga os mais antigos sozinho, por um índice TTL
 */
@ConfigurationProperties("app.audit")
@Profile("!seed")
public record AuditProperties(String hashSecret, Duration retention) {

    public static final int MIN_SECRET_BYTES = 32;

    public AuditProperties {
        if (hashSecret == null || hashSecret.length() < MIN_SECRET_BYTES) {
            throw new IllegalArgumentException("AUDIT_HASH_SECRET must have at least " + MIN_SECRET_BYTES + " characters");
        }
        if (retention == null || retention.isZero() || retention.isNegative()) {
            throw new IllegalArgumentException("AUDIT_RETENTION must be positive");
        }
    }
}
