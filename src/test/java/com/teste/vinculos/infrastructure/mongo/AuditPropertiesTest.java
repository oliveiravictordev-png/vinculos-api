package com.teste.vinculos.infrastructure.mongo;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuditPropertiesTest {

    private static final String SECRET = "x".repeat(AuditProperties.MIN_SECRET_BYTES);

    @Test
    void requiresALongSecretAndAPositiveRetention() {
        assertThatThrownBy(() -> new AuditProperties(null, Duration.ofDays(90))).hasMessageContaining("AUDIT_HASH_SECRET");
        assertThatThrownBy(() -> new AuditProperties("short", Duration.ofDays(90))).hasMessageContaining("AUDIT_HASH_SECRET");
        assertThatThrownBy(() -> new AuditProperties(SECRET, null)).hasMessageContaining("AUDIT_RETENTION");
        assertThatThrownBy(() -> new AuditProperties(SECRET, Duration.ZERO)).hasMessageContaining("AUDIT_RETENTION");
        assertThatThrownBy(() -> new AuditProperties(SECRET, Duration.ofDays(-1))).hasMessageContaining("AUDIT_RETENTION");
        assertThat(new AuditProperties(SECRET, Duration.ofDays(90)).retention()).hasDays(90);
    }
}
