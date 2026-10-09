package com.teste.vinculos.web.security;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.teste.vinculos.support.TestKeys;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import java.time.Clock;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtKeysTest {

    @Test
    void keyIdIsTheThumbprintAndPublicSetHasNoPrivatePart() {
        JwtKeys keys = JwtKeys.from(properties(TestKeys.privateKey(), ""));

        assertThat(keys.signing().getKeyID()).isNotBlank();
        assertThat(keys.publicKeys().getKeys()).singleElement().satisfies(k -> {
            assertThat(k.getKeyID()).isEqualTo(keys.signing().getKeyID());
            assertThat(k.isPrivate()).isFalse();
        });
    }

    @Test
    void tokensFromThePreviousKeyStayValidDuringRotation() {
        AuthProperties before = properties(TestKeys.privateKey(), "");
        String oldToken = tokens(before).issueAccess("gft-admin", List.of(Scopes.CUSTOMERS_READ), "sid").value();

        AuthProperties rotated = properties(TestKeys.otherPrivateKey(), TestKeys.publicKeyPem());
        AuthProperties withoutPrevious = properties(TestKeys.otherPrivateKey(), "");

        assertThat(TokenService.decoder(JwtKeys.from(rotated), rotated).decode(oldToken).getSubject()).isEqualTo("gft-admin");
        assertThatThrownBy(() -> TokenService.decoder(JwtKeys.from(withoutPrevious), withoutPrevious).decode(oldToken))
                .isInstanceOf(JwtException.class);
    }

    @Test
    void invalidKeyFailsAtStartupWithoutEchoingTheValue() {
        assertThatThrownBy(() -> JwtKeys.from(properties("bm90LWEta2V5", "")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining("bm90LWEta2V5");
    }

    @Test
    void propertiesRejectMissingKeyAndRefreshShorterThanAccess() {
        assertThatThrownBy(() -> properties("", "")).hasMessageContaining("JWT_PRIVATE_KEY");
        assertThatThrownBy(() -> new AuthProperties("a", "p", "b", "p", TestKeys.privateKey(), null, "iss",
                Duration.ofHours(1), Duration.ofMinutes(5))).hasMessageContaining("JWT_REFRESH_TTL");
    }

    private static TokenService tokens(AuthProperties properties) {
        JwtKeys keys = JwtKeys.from(properties);
        var encoder = new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(keys.signing())));
        return new TokenService(encoder, keys, properties, Clock.systemUTC());
    }

    private static AuthProperties properties(String privateKey, String previousPublicKey) {
        return new AuthProperties("a", "p", "b", "p", privateKey, previousPublicKey, "iss",
                Duration.ofMinutes(5), Duration.ofHours(1));
    }
}
