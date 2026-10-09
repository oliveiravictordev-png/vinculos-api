package com.teste.vinculos.infrastructure.mongo;

import com.mongodb.ReadConcern;
import com.mongodb.ReadPreference;
import com.mongodb.WriteConcern;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.FindOneAndUpdateOptions;
import com.mongodb.client.model.ReturnDocument;
import com.mongodb.client.model.Updates;
import com.teste.vinculos.domain.LoginAttemptStore;
import org.bson.Document;
import org.springframework.context.annotation.Profile;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Date;

import static com.mongodb.client.model.Filters.eq;

/**
 * Tentativas de login erradas na coleção {@value #COLLECTION}, um documento por usuário ({@code _id}). O
 * {@code $inc} com upsert é atômico, então duas instâncias recebendo tentativas ao mesmo tempo somam certo. O índice
 * TTL em {@code expiresAt} apaga o registro quando a contagem deve ser esquecida (e nunca antes do fim do bloqueio).
 */
@Component
@Profile("!seed")
public class MongoLoginAttemptStore implements LoginAttemptStore {

    static final String COLLECTION = "login_attempts";

    private final MongoCollection<Document> attempts;

    public MongoLoginAttemptStore(MongoTemplate mongo) {
        this.attempts = mongo.getCollection(COLLECTION)
                .withWriteConcern(WriteConcern.MAJORITY)
                .withReadConcern(ReadConcern.MAJORITY)
                .withReadPreference(ReadPreference.primaryPreferred());
        MongoIndexes.ensureTtl(mongo.getDb(), COLLECTION, "expiresAt", "ix_expires_at_ttl", Duration.ZERO);
    }

    @Override
    public int recordFailure(String username, Instant forgetAt) {
        Document updated = attempts.findOneAndUpdate(eq("_id", username),
                Updates.combine(Updates.inc("failures", 1), Updates.max("expiresAt", Date.from(forgetAt))),
                new FindOneAndUpdateOptions().upsert(true).returnDocument(ReturnDocument.AFTER));
        return updated == null ? 1 : updated.getInteger("failures", 1);
    }

    @Override
    public void lockUntil(String username, Instant until) {
        attempts.updateOne(eq("_id", username), Updates.combine(
                Updates.set("lockedUntil", Date.from(until)),
                Updates.max("expiresAt", Date.from(until))));
    }

    @Override
    public Instant lockedUntil(String username) {
        Document attempt = attempts.find(eq("_id", username)).first();
        Date until = attempt == null ? null : attempt.getDate("lockedUntil");
        return until == null ? null : until.toInstant();
    }

    @Override
    public void clear(String username) {
        attempts.deleteOne(eq("_id", username));
    }
}
