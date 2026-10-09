package com.teste.vinculos.web.security;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;

import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * Chaves RSA dos JWTs. Assinatura assimétrica (RS256) em vez de segredo compartilhado (HS256): só a API assina, e
 * qualquer serviço valida com a chave pública, sem poder emitir tokens.
 *
 * <p>O {@code kid} de cada chave é o seu thumbprint (RFC 7638): não precisa de configuração e muda sozinho quando a
 * chave muda. Na rotação, a chave antiga vira {@code JWT_PREVIOUS_PUBLIC_KEY} e o validador escolhe a chave pelo
 * {@code kid} do token.
 */
final class JwtKeys {

    private final RSAKey signing;
    private final JWKSet publicKeys;

    private JwtKeys(RSAKey signing, JWKSet publicKeys) {
        this.signing = signing;
        this.publicKeys = publicKeys;
    }

    static JwtKeys from(AuthProperties properties) {
        try {
            var privateKey = (RSAPrivateCrtKey) KeyFactory.getInstance("RSA")
                    .generatePrivate(new PKCS8EncodedKeySpec(decode(properties.jwtPrivateKey())));
            // A chave privada CRT carrega o módulo e o expoente público: a pública sai dela, sem outra variável.
            var publicKey = (RSAPublicKey) KeyFactory.getInstance("RSA")
                    .generatePublic(new RSAPublicKeySpec(privateKey.getModulus(), privateKey.getPublicExponent()));
            RSAKey signing = jwk(publicKey).privateKey(privateKey).build();
            var keys = new ArrayList<>(List.of(signing.toPublicJWK()));
            if (!properties.jwtPreviousPublicKey().isBlank()) {
                var previous = (RSAPublicKey) KeyFactory.getInstance("RSA")
                        .generatePublic(new X509EncodedKeySpec(decode(properties.jwtPreviousPublicKey())));
                keys.add(jwk(previous).build());
            }
            return new JwtKeys(signing, new JWKSet(List.copyOf(keys)));
        } catch (GeneralSecurityException | IllegalArgumentException | ClassCastException | JOSEException e) {
            // A mensagem nunca inclui o valor da chave.
            throw new IllegalArgumentException("JWT_PRIVATE_KEY / JWT_PREVIOUS_PUBLIC_KEY must be RSA keys in Base64 or PEM", e);
        }
    }

    /** Chave privada de assinatura (com o {@code kid}). */
    RSAKey signing() {
        return signing;
    }

    /** Chaves públicas aceitas na validação: a atual e, durante uma rotação, a anterior. */
    JWKSet publicKeys() {
        return publicKeys;
    }

    private static RSAKey.Builder jwk(RSAPublicKey key) throws JOSEException {
        return new RSAKey.Builder(key).keyUse(KeyUse.SIGNATURE).algorithm(JWSAlgorithm.RS256).keyIDFromThumbprint();
    }

    // Aceita PEM (com cabeçalhos e quebras de linha) ou só o Base64, que cabe numa linha do .env.
    private static byte[] decode(String value) {
        return Base64.getMimeDecoder().decode(value.replaceAll("-----[A-Z ]+-----", ""));
    }
}
