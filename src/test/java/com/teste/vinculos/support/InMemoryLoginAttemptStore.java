package com.teste.vinculos.support;

import com.teste.vinculos.domain.LoginAttemptStore;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Tentativas de login em memória (fake): mesma semântica do MongoLoginAttemptStore, sem expiração automática. */
public class InMemoryLoginAttemptStore implements LoginAttemptStore {

    private record Attempt(int failures, Instant lockedUntil) {
    }

    private final Map<String, Attempt> attempts = new ConcurrentHashMap<>();

    @Override
    public int recordFailure(String username, Instant forgetAt) {
        return attempts.merge(username, new Attempt(1, null),
                (old, one) -> new Attempt(old.failures() + 1, old.lockedUntil())).failures();
    }

    @Override
    public void lockUntil(String username, Instant until) {
        attempts.computeIfPresent(username, (key, old) -> new Attempt(old.failures(), until));
    }

    @Override
    public Instant lockedUntil(String username) {
        Attempt attempt = attempts.get(username);
        return attempt == null ? null : attempt.lockedUntil();
    }

    @Override
    public void clear(String username) {
        attempts.remove(username);
    }

    public void clearAll() {
        attempts.clear();
    }
}
