package com.teste.vinculos.domain;

import java.time.Instant;

/**
 * Porta de saída das tentativas de login erradas, por usuário. Fica fora da memória da instância: com duas instâncias
 * atrás do balanceador, um contador local deixaria o atacante errar o dobro de vezes.
 */
public interface LoginAttemptStore {

    /**
     * Soma uma falha seguida do usuário e devolve o total. A contagem é esquecida em {@code forgetAt} se não houver
     * outra falha até lá.
     */
    int recordFailure(String username, Instant forgetAt);

    /** Bloqueia o usuário até {@code until} (o registro dura pelo menos até lá). */
    void lockUntil(String username, Instant until);

    /** Até quando o usuário está bloqueado, ou {@code null} se não está. */
    Instant lockedUntil(String username);

    /** Zera a contagem (login certo). */
    void clear(String username);
}
