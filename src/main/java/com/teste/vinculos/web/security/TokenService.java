package com.teste.vinculos.web.security;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;
import org.springframework.context.annotation.Profile;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Emite e valida os dois tipos de token. O claim {@code type} separa os dois: um refresh token roubado não abre
 * nenhum endpoint de dados, e um access token não renova sessão.
 *
 * <ul>
 *   <li><b>access</b>: curto ({@code JWT_TTL}), com os escopos; vai no header Authorization ou no cookie HttpOnly.</li>
 *   <li><b>refresh</b>: só no cookie HttpOnly restrito a /api/v1/auth, válido até o fim da sessão.</li>
 * </ul>
 * Os dois carregam o {@code sid} da sessão no banco, que é o que permite revogá-los.
 */
@Service
@Profile("!seed")
public class TokenService {

    static final String TYPE = "type";
    static final String SESSION_ID = "sid";
    static final String ACCESS = "access";
    static final String REFRESH = "refresh";

    private final JwtEncoder encoder;
    private final JwtDecoder refreshDecoder;
    private final JwtKeys keys;
    private final AuthProperties properties;
    private final Clock clock;

    public TokenService(JwtEncoder encoder, JwtKeys keys, AuthProperties properties, Clock clock) {
        this.encoder = encoder;
        this.keys = keys;
        this.properties = properties;
        this.clock = clock;
        this.refreshDecoder = decoder(keys, properties, typeIs(REFRESH));
    }

    public IssuedToken issueAccess(String username, Collection<String> scopes, String sessionId) {
        Instant now = clock.instant();
        return issue(JwtClaimsSet.builder()
                .subject(username)
                .issuedAt(now)
                .expiresAt(now.plus(properties.ttl()))
                .claim(TYPE, ACCESS)
                .claim(SESSION_ID, sessionId)
                .claim("scope", String.join(" ", scopes)));
    }

    public IssuedToken issueRefresh(String username, String sessionId, Instant sessionExpiresAt) {
        return issue(JwtClaimsSet.builder()
                .subject(username)
                .issuedAt(clock.instant())
                .expiresAt(sessionExpiresAt)
                .claim(TYPE, REFRESH)
                .claim(SESSION_ID, sessionId));
    }

    /** Valida assinatura, emissor, validade e tipo do refresh token; qualquer falha vira 401. */
    public Jwt decodeRefresh(String token) {
        try {
            return refreshDecoder.decode(token);
        } catch (JwtException e) {
            throw new InvalidSessionException("Invalid refresh token");
        }
    }

    private IssuedToken issue(JwtClaimsSet.Builder claims) {
        var built = claims.issuer(properties.issuer()).id(UUID.randomUUID().toString()).build();
        var header = JwsHeader.with(SignatureAlgorithm.RS256).keyId(keys.signing().getKeyID()).build();
        String value = encoder.encode(JwtEncoderParameters.from(header, built)).getTokenValue();
        return new IssuedToken(value, built.getExpiresAt());
    }

    /**
     * Decodificador RS256 que escolhe a chave pelo {@code kid} entre as chaves públicas aceitas, com as validações
     * padrão (emissor, validade) mais as recebidas.
     */
    @SafeVarargs
    static NimbusJwtDecoder decoder(JwtKeys keys, AuthProperties properties, OAuth2TokenValidator<Jwt>... extra) {
        var processor = new DefaultJWTProcessor<SecurityContext>();
        processor.setJWSKeySelector(new JWSVerificationKeySelector<>(JWSAlgorithm.RS256,
                new ImmutableJWKSet<>(keys.publicKeys())));
        processor.setJWTClaimsSetVerifier((claims, context) -> { }); // claims conferidos pelos validadores do Spring
        var decoder = new NimbusJwtDecoder(processor);
        var validators = new ArrayList<OAuth2TokenValidator<Jwt>>();
        validators.add(JwtValidators.createDefaultWithIssuer(properties.issuer()));
        validators.addAll(List.of(extra));
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(validators));
        return decoder;
    }

    static OAuth2TokenValidator<Jwt> typeIs(String type) {
        return new JwtClaimValidator<String>(TYPE, type::equals);
    }

    public record IssuedToken(String value, Instant expiresAt) {
    }
}
