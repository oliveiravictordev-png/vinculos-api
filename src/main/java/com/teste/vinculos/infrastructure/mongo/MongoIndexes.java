package com.teste.vinculos.infrastructure.mongo;

import com.mongodb.MongoCommandException;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import org.bson.Document;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/** Criação de índices das coleções auxiliares (auditoria e sessões), idempotente entre instâncias e versões. */
final class MongoIndexes {

    // "Já existe um índice com esse nome, mas com outras opções."
    private static final int INDEX_OPTIONS_CONFLICT = 85;

    private MongoIndexes() {
    }

    /**
     * Índice TTL em {@code field}. Se a retenção mudou desde a criação, o createIndex é recusado; nesse caso o
     * collMod ajusta o tempo no índice existente, sem recriá-lo (recriar varreria a coleção inteira).
     */
    static void ensureTtl(MongoDatabase db, String collection, String field, String name, Duration ttl) {
        try {
            db.getCollection(collection).createIndex(Indexes.ascending(field),
                    new IndexOptions().name(name).expireAfter(ttl.toSeconds(), TimeUnit.SECONDS));
        } catch (MongoCommandException e) {
            if (e.getErrorCode() != INDEX_OPTIONS_CONFLICT) {
                throw e;
            }
            db.runCommand(new Document("collMod", collection)
                    .append("index", new Document("name", name).append("expireAfterSeconds", ttl.toSeconds())));
        }
    }
}
