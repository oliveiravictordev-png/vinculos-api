package com.teste.vinculos.infrastructure.mongo;

import com.mongodb.MongoCommandException;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.ClusteredIndexOptions;
import com.mongodb.client.model.CreateCollectionOptions;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.ValidationAction;
import com.mongodb.client.model.ValidationLevel;
import com.mongodb.client.model.ValidationOptions;
import com.teste.vinculos.domain.CustomerKey;
import com.teste.vinculos.domain.DocumentType;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;

import static com.mongodb.client.model.Filters.eq;

/** Cria a coleção (clusterizada por _id, com validação de schema e compressão zstd) e o índice de consulta. */
@Component
public class MongoSchema {

    // Nome mantido por compatibilidade: renomear exigiria recriar o índice em bases já carregadas.
    public static final String INDEX = "ix_ano_tipo_documento_empresa";

    private static final Logger log = LogManager.getLogger(MongoSchema.class);
    private static final int NAMESPACE_EXISTS = 48;

    // Formato dos documentos já normalizados: CPF com 11 dígitos; CNPJ com 12 posições [0-9A-Z] + 2 dígitos verificadores.
    private static final String CNPJ_PATTERN = "[0-9A-Z]{12}[0-9]{2}";
    private static final String CPF_PATTERN = "[0-9]{11}";

    // O banco recusa qualquer documento fora do formato: garantia de integridade independente da aplicação.
    // Montado a partir de Fields e do domínio, para não divergir dos nomes de campo e das regras da aplicação.
    private static final Document VALIDATOR = new Document("$jsonSchema", new Document("bsonType", "object")
            .append("required", List.of(Fields.ID, Fields.YEAR, Fields.TYPE, Fields.DOCUMENT,
                    Fields.COMPANY, Fields.PRODUCT, Fields.AMOUNT_CENTS, Fields.UPDATED_AT))
            .append("properties", new Document()
                    .append(Fields.ID, type("long"))
                    .append(Fields.YEAR, type("int").append("minimum", CustomerKey.MIN_YEAR).append("maximum", CustomerKey.MAX_YEAR))
                    .append(Fields.TYPE, new Document("enum", Arrays.stream(DocumentType.values()).map(Enum::name).toList()))
                    .append(Fields.DOCUMENT, type("string").append("pattern", "^(" + CPF_PATTERN + "|" + CNPJ_PATTERN + ")$"))
                    .append(Fields.COMPANY, type("string").append("pattern", "^" + CNPJ_PATTERN + "$"))
                    .append(Fields.PRODUCT, type("string"))
                    .append(Fields.AMOUNT_CENTS, type("long"))
                    .append(Fields.UPDATED_AT, type("date"))));

    private final MongoDatabase db;

    public MongoSchema(MongoTemplate mongo) {
        this.db = mongo.getDb();
    }

    /** Validador JSON Schema aplicado à coleção (exposto para os testes). */
    static Document validator() {
        return VALIDATOR;
    }

    private static Document type(String bsonType) {
        return new Document("bsonType", bsonType);
    }

    public void ensureCollection() {
        if (db.listCollections().filter(eq("name", Fields.COLLECTION)).first() != null) {
            return;
        }
        try {
            createCollection(true);
            log.info("Collection {} created (clustered by _id, schema validation, zstd compression)", Fields.COLLECTION);
        } catch (MongoCommandException e) {
            if (e.getErrorCode() == NAMESPACE_EXISTS) {
                return;
            }
            // Serviços gerenciados (ex.: MongoDB Atlas) proíbem storageEngine e já aplicam compressão própria.
            if (!e.getErrorMessage().contains("storageEngine")) {
                throw e;
            }
            log.warn("Server does not allow storageEngine options; creating {} with the default compression", Fields.COLLECTION);
            createCollection(false);
            log.info("Collection {} created (clustered by _id, schema validation)", Fields.COLLECTION);
        }
    }

    // Clusterizada: os documentos ficam ordenados pelo próprio _id, sem o índice _id separado
    // (em 1 bilhão de registros, ~32 GB a menos em disco e menos escrita na carga).
    private void createCollection(boolean zstd) {
        var options = new CreateCollectionOptions()
                .clusteredIndexOptions(new ClusteredIndexOptions(new Document(Fields.ID, 1), true))
                .validationOptions(new ValidationOptions()
                        .validator(VALIDATOR)
                        .validationLevel(ValidationLevel.STRICT)
                        .validationAction(ValidationAction.ERROR));
        if (zstd) {
            options.storageEngineOptions(new Document("wiredTiger",
                    new Document("configString", "block_compressor=zstd")));
        }
        db.createCollection(Fields.COLLECTION, options);
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
