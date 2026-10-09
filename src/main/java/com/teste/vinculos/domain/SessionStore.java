package com.teste.vinculos.domain;

import java.time.Instant;

/**
 * Porta de saída das sessões de login. Fica fora da memória da instância para que logout e revogação valham em
 * todas as instâncias da API atrás do balanceador.
 */
public interface SessionStore {

    /** Abre uma sessão que expira sozinha em {@code expiresAt} e devolve o seu ID (aleatório, não adivinhável). */
    String create(String username, Instant expiresAt);

    /** A sessão existe, pertence ao usuário, não expirou e não foi revogada. */
    boolean isActive(String sessionId, String username);

    /** Revoga a sessão (idempotente). */
    void revoke(String sessionId);
}
