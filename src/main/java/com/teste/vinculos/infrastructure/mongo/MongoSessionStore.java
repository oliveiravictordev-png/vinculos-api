package com.teste.vinculos.infrastructure.mongo;

import com.mongodb.ReadConcern;
import com.mongodb.ReadPreference;
import com.mongodb.WriteConcern;
import com.mongodb.client.MongoCollection;
import com.teste.vinculos.domain.SessionStore;
import org.bson.Document;
import org.springframework.context.annotation.Profile;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;

import static com.mongodb.client.model.Filters.and;
import static com.mongodb.client.model.Filters.eq;
import static com.mongodb.client.model.Filters.gt;
import static com.mongodb.client.model.Updates.set;

/**
 * Sessões de login na coleção {@value #COLLECTION}, compartilhada pelas instâncias da API. Gravação e leitura
 * majority: um logout confirmado não volta depois de uma troca de primário. O índice TTL apaga as sessões vencidas
 * (o MongoDB passa a cada ~60 s, por isso a consulta também confere o vencimento).
 */
@Component
@Profile("!seed")
public class MongoSessionStore implements SessionStore {

    static final String COLLECTION = "auth_sessions";

    private static final SecureRandom RANDOM = new SecureRandom();

    private final MongoCollection<Document> sessions;
    private final Clock clock;

    public MongoSessionStore(MongoTemplate mongo, Clock clock) {
        this.sessions = mongo.getCollection(COLLECTION)
                .withWriteConcern(WriteConcern.MAJORITY)
                .withReadConcern(ReadConcern.MAJORITY)
                .withReadPreference(ReadPreference.primaryPreferred());
        this.clock = clock;
        MongoIndexes.ensureTtl(mongo.getDb(), COLLECTION, "expiresAt", "ix_expires_at_ttl", Duration.ZERO);
    }

    @Override
    public String create(String username, Instant expiresAt) {
        // 128 bits aleatórios: o ID vai dentro do JWT e não pode ser adivinhado.
        byte[] bytes = new byte[16];
        RANDOM.nextBytes(bytes);
        String id = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        sessions.insertOne(new Document("_id", id)
                .append("username", username)
                .append("createdAt", Date.from(clock.instant()))
                .append("expiresAt", Date.from(expiresAt))
                .append("revoked", false));
        return id;
    }

    @Override
    public boolean isActive(String sessionId, String username) {
        if (sessionId == null || username == null) {
            return false;
        }
        return sessions.find(and(eq("_id", sessionId), eq("username", username), eq("revoked", false),
                gt("expiresAt", Date.from(clock.instant())))).limit(1).first() != null;
    }

    @Override
    public void revoke(String sessionId) {
        if (sessionId != null) {
            sessions.updateOne(eq("_id", sessionId), set("revoked", true));
        }
    }
}
