package com.teste.vinculos.support;

import com.teste.vinculos.domain.SessionStore;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Sessões em memória (fake): mesma semântica do MongoSessionStore, sem banco. */
public class InMemorySessionStore implements SessionStore {

    private record Session(String username, Instant expiresAt, boolean revoked) {
    }

    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    public int lookups;

    @Override
    public String create(String username, Instant expiresAt) {
        String id = UUID.randomUUID().toString();
        sessions.put(id, new Session(username, expiresAt, false));
        return id;
    }

    @Override
    public boolean isActive(String sessionId, String username) {
        lookups++;
        Session session = sessions.get(sessionId);
        return session != null && session.username().equals(username) && !session.revoked()
                && session.expiresAt().isAfter(Instant.now());
    }

    @Override
    public void revoke(String sessionId) {
        sessions.computeIfPresent(sessionId, (id, s) -> new Session(s.username(), s.expiresAt(), true));
    }
}
