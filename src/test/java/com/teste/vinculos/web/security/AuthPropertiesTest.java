package com.teste.vinculos.web.security;

import com.teste.vinculos.support.TestKeys;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** A API não sobe com configuração de autenticação incompleta: falha cedo, com a variável que falta na mensagem. */
class AuthPropertiesTest {

    private static final String KEY = TestKeys.privateKey();
    private static final Duration TTL = Duration.ofMinutes(15);
    private static final Duration REFRESH = Duration.ofHours(12);

    static Stream<Arguments> invalid() {
        return Stream.of(
                Arguments.of(null, "p", "b", "p", KEY, "iss", TTL, REFRESH, "AUTH_ADMIN1_USERNAME"),
                Arguments.of("a", " ", "b", "p", KEY, "iss", TTL, REFRESH, "AUTH_ADMIN1_PASSWORD"),
                Arguments.of("a", "p", "", "p", KEY, "iss", TTL, REFRESH, "AUTH_ADMIN2_USERNAME"),
                Arguments.of("a", "p", "b", null, KEY, "iss", TTL, REFRESH, "AUTH_ADMIN2_PASSWORD"),
                Arguments.of("a", "p", "a", "p", KEY, "iss", TTL, REFRESH, "must be different"),
                Arguments.of("a", "p", "b", "p", null, "iss", TTL, REFRESH, "JWT_PRIVATE_KEY"),
                Arguments.of("a", "p", "b", "p", KEY, " ", TTL, REFRESH, "JWT_ISSUER"),
                Arguments.of("a", "p", "b", "p", KEY, "iss", null, REFRESH, "JWT_TTL"),
                Arguments.of("a", "p", "b", "p", KEY, "iss", Duration.ZERO, REFRESH, "JWT_TTL"),
                Arguments.of("a", "p", "b", "p", KEY, "iss", TTL, null, "JWT_REFRESH_TTL"));
    }

    @ParameterizedTest
    @MethodSource("invalid")
    void rejectsIncompleteConfiguration(String user1, String pass1, String user2, String pass2, String key,
                                        String issuer, Duration ttl, Duration refresh, String message) {
        assertThatThrownBy(() -> new AuthProperties(user1, pass1, user2, pass2, key, null, issuer, ttl, refresh))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(message);
    }

    @Test
    void previousKeyIsOptional() {
        assertThat(new AuthProperties("a", "p", "b", "p", KEY, null, "iss", TTL, REFRESH).jwtPreviousPublicKey()).isEmpty();
    }
}
