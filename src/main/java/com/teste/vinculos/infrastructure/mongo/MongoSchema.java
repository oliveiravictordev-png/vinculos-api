package com.teste.vinculos.infrastructure.mongo;

import com.mongodb.MongoCommandException;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.CreateCollectionOptions;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.ValidationAction;
import com.mongodb.client.model.ValidationLevel;
import com.mongodb.client.model.ValidationOptions;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Component;

import static com.mongodb.client.model.Filters.eq;

/** Cria a coleção (com validação de schema e compressão zstd) e o índice de consulta. */
@Component
public class MongoSchema {

    // Nome mantido por compatibilidade: renomear exigiria recriar o índice em bases já carregadas.
    public static final String INDEX = "ix_ano_tipo_documento_empresa";

    private static final Logger log = LogManager.getLogger(MongoSchema.class);
    private static final int NAMESPACE_EXISTS = 48;

    // O banco recusa qualquer documento fora do formato: garantia de integridade independente da aplicação.
    private static final Document VALIDATOR = Document.parse("""
            { "$jsonSchema": {
                "bsonType": "object",
                "required": ["_id", "a", "t", "v", "e", "p", "s", "u"],
                "properties": {
                  "_id": { "bsonType": "long" },
                  "a":   { "bsonType": "int", "minimum": 1900, "maximum": 2100 },
                  "t":   { "enum": ["CPF", "CNPJ"] },
                  "v":   { "bsonType": "string", "pattern": "^([0-9]{11}|[0-9A-Z]{12}[0-9]{2})$" },
                  "e":   { "bsonType": "string", "pattern": "^[0-9A-Z]{12}[0-9]{2}$" },
                  "p":   { "bsonType": "string" },
                  "s":   { "bsonType": "long" },
                  "u":   { "bsonType": "date" }
                }
            } }
            """);

    private final MongoDatabase db;

    public MongoSchema(MongoTemplate mongo) {
        this.db = mongo.getDb();
    }

    public void ensureCollection() {
        if (db.listCollections().filter(eq("name", Fields.COLLECTION)).first() != null) {
            return;
        }
        try {
            db.createCollection(Fields.COLLECTION, new CreateCollectionOptions()
                    .validationOptions(new ValidationOptions()
                            .validator(VALIDATOR)
                            .validationLevel(ValidationLevel.STRICT)
                            .validationAction(ValidationAction.ERROR))
                    .storageEngineOptions(new Document("wiredTiger",
                            new Document("configString", "block_compressor=zstd"))));
            log.info("Collection {} created (schema validation, zstd compression)", Fields.COLLECTION);
        } catch (MongoCommandException e) {
            if (e.getErrorCode() != NAMESPACE_EXISTS) {
                throw e;
            }
        }
    }

    /**
     * Índice ano + tipo + documento + empresa. O prefixo (ano, tipo, documento) atende a chave do cliente;
     * a empresa no final torna o endpoint 1 uma consulta coberta (DISTINCT_SCAN, sem ler documentos)
     * e o filtro $in do endpoint 2 é resolvido no próprio índice.
     */
    public void ensureIndex() {
        long start = System.nanoTime();
        db.getCollection(Fields.COLLECTION).createIndex(
                Indexes.ascending(Fields.YEAR, Fields.TYPE, Fields.DOCUMENT, Fields.COMPANY),
                new IndexOptions().name(INDEX));
        log.info("Index {} ensured in {} ms", INDEX, (System.nanoTime() - start) / 1_000_000);
    }
}
