package com.teste.vinculos.application;

import com.teste.vinculos.domain.LoginAttemptStore;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;

/**
 * Proteção do login contra tentativa e erro, por usuário: depois de {@value #FREE_FAILURES} senhas erradas seguidas o
 * usuário fica bloqueado por {@link #FIRST_LOCK}, e cada nova falha dobra o tempo até {@link #MAX_LOCK}. Um login
 * certo zera a contagem; sem falhas por {@link #FORGET_AFTER}, ela é esquecida.
 *
 * <ul>
 *   <li><b>Por usuário, não por IP:</b> o front chega pela Vercel, então todos os usuários dele aparecem com o mesmo
 *       IP; um bloqueio por IP travaria todo mundo. Por IP há só um rate limit menor (RateLimitFilter), que atrasa
 *       rajadas sem bloquear.</li>
 *   <li><b>Bloqueio curto e progressivo:</b> torna inviável testar senhas (em 1 h, umas 10 tentativas por usuário em
 *       vez de milhares), sem deixar o dono da conta preso por muito tempo se alguém errar a senha dele de propósito.</li>
 *   <li>Durante o bloqueio a senha nem é conferida: o atacante não descobre se acertou.</li>
 * </ul>
 */
public class LoginThrottle {

    public static final int FREE_FAILURES = 5;
    public static final Duration FIRST_LOCK = Duration.ofMinutes(1);
    public static final Duration MAX_LOCK = Duration.ofMinutes(15);
    public static final Duration FORGET_AFTER = Duration.ofHours(1);
    /** Nomes maiores são cortados: ninguém consegue criar registros enormes com nomes inventados. */
    public static final int MAX_USERNAME_LENGTH = 64;

    private final LoginAttemptStore store;
    private final Clock clock;

    public LoginThrottle(LoginAttemptStore store, Clock clock) {
        this.store = store;
        this.clock = clock;
    }

    /** Tempo de bloqueio que ainda falta para o usuário ({@link Duration#ZERO} se pode tentar). */
    public Duration remainingLock(String username) {
        Instant until = store.lockedUntil(key(username));
        Instant now = clock.instant();
        return until == null || !until.isAfter(now) ? Duration.ZERO : Duration.between(now, until);
    }

    /** Registra uma senha errada e devolve o bloqueio que ela causou ({@link Duration#ZERO} se nenhum). */
    public Duration failed(String username) {
        String key = key(username);
        Instant now = clock.instant();
        int failures = store.recordFailure(key, now.plus(FORGET_AFTER));
        if (failures < FREE_FAILURES) {
            return Duration.ZERO;
        }
        Duration lock = lockFor(failures);
        store.lockUntil(key, now.plus(lock));
        return lock;
    }

    public void succeeded(String username) {
        store.clear(key(username));
    }

    /** 5ª falha: 1 min; 6ª: 2 min; 7ª: 4 min; 8ª: 8 min; da 9ª em diante: 15 min. */
    static Duration lockFor(int failures) {
        int doublings = Math.min(failures - FREE_FAILURES, 10);
        Duration lock = FIRST_LOCK.multipliedBy(1L << doublings);
        return lock.compareTo(MAX_LOCK) > 0 ? MAX_LOCK : lock;
    }

    // "Admin" e "admin " contam como o mesmo usuário: variar maiúsculas ou espaços não zera a contagem.
    private static String key(String username) {
        String key = username == null ? "" : username.trim().toLowerCase(Locale.ROOT);
        return key.length() > MAX_USERNAME_LENGTH ? key.substring(0, MAX_USERNAME_LENGTH) : key;
    }
}
