package com.teste.vinculos.support;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

/** Chaves RSA geradas na hora para os testes: nenhuma chave privada fica no repositório. */
public final class TestKeys {

    private static final KeyPair CURRENT = generate();
    private static final KeyPair OTHER = generate();

    private TestKeys() {
    }

    /** Chave privada atual, no formato de JWT_PRIVATE_KEY (PKCS#8 em Base64). */
    public static String privateKey() {
        return Base64.getEncoder().encodeToString(CURRENT.getPrivate().getEncoded());
    }

    /** Outra chave privada, para simular a chave nova de uma rotação. */
    public static String otherPrivateKey() {
        return Base64.getEncoder().encodeToString(OTHER.getPrivate().getEncoded());
    }

    /** Chave pública atual, no formato de JWT_PREVIOUS_PUBLIC_KEY (X.509 em PEM). */
    public static String publicKeyPem() {
        return "-----BEGIN PUBLIC KEY-----\n"
                + Base64.getMimeEncoder().encodeToString(CURRENT.getPublic().getEncoded())
                + "\n-----END PUBLIC KEY-----\n";
    }

    private static KeyPair generate() {
        try {
            var generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
