package com.teste.vinculos.infrastructure.mongo;

import com.mongodb.ReadPreference;
import com.mongodb.WriteConcern;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import com.teste.vinculos.domain.AuditEntry;
import com.teste.vinculos.domain.DocumentType;
import com.teste.vinculos.domain.Documents;
import com.teste.vinculos.domain.QueryAuditLog;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bson.Document;
import org.springframework.context.annotation.Profile;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.Date;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;

/**
 * Trilha de auditoria na coleção {@value #COLLECTION}, separada da base de vínculos (que é só leitura).
 *
 * <ul>
 *   <li><b>Assíncrona:</b> a gravação roda numa virtual thread, então a consulta não espera o write concern
 *       majority. Até {@value #MAX_PENDING} gravações pendentes; acima disso (banco travado) o registro é descartado
 *       com log de erro, em vez de acumular memória até derrubar a API.</li>
 *   <li><b>LGPD:</b> grava o documento mascarado (para o histórico) e um HMAC-SHA256 dele (para achar as consultas
 *       a um titular), nunca o documento completo. Os registros expiram pelo índice TTL.</li>
 *   <li>Nomes de campo legíveis: a coleção é pequena (uma linha por consulta, com expiração), ao contrário dos
 *       vínculos.</li>
 * </ul>
 */
@Component
@Profile("!seed")
public class MongoQueryAuditLog implements QueryAuditLog, AutoCloseable {

    public static final String COLLECTION = "query_audit";
    static final int MAX_PENDING = 1_000;

    private static final Logger log = LogManager.getLogger(MongoQueryAuditLog.class);

    private final MongoCollection<Document> audits;
    private final SecretKeySpec hashKey;
    private final ExecutorService writer = Executors.newVirtualThreadPerTaskExecutor();
    private final Semaphore pending = new Semaphore(MAX_PENDING);

    public MongoQueryAuditLog(MongoTemplate mongo, AuditProperties properties) {
        this.audits = mongo.getCollection(COLLECTION)
                .withWriteConcern(WriteConcern.MAJORITY)
                .withReadPreference(ReadPreference.primaryPreferred());
        this.hashKey = new SecretKeySpec(properties.hashSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        audits.createIndex(Indexes.compoundIndex(Indexes.ascending("username"), Indexes.descending("at")),
                new IndexOptions().name("ix_username_at"));
        audits.createIndex(Indexes.ascending("documentHash"), new IndexOptions().name("ix_document_hash"));
        MongoIndexes.ensureTtl(mongo.getDb(), COLLECTION, "at", "ix_at_ttl", properties.retention());
    }

    @Override
    public void record(AuditEntry entry) {
        Document document = toDocument(entry);
        if (!pending.tryAcquire()) {
            log.error("Audit entry dropped: {} pending writes ({} {} by {})",
                    MAX_PENDING, entry.action(), entry.key(), entry.username());
            return;
        }
        writer.execute(() -> {
            try {
                audits.insertOne(document);
            } catch (RuntimeException e) {
                log.error("Audit entry not saved ({} {} by {})", entry.action(), entry.key(), entry.username(), e);
            } finally {
                pending.release();
            }
        });
    }

    @Override
    public List<AuditEntry.View> recent(String username, int limit) {
        var views = new ArrayList<AuditEntry.View>(limit);
        for (Document d : audits.find(new Document("username", username))
                .sort(new Document("at", -1)).limit(limit)) {
            views.add(new AuditEntry.View(
                    AuditEntry.Action.valueOf(d.getString("action")),
                    d.getDate("at").toInstant(),
                    d.getInteger("year"),
                    DocumentType.valueOf(d.getString("documentType")),
                    d.getString("documentMasked"),
                    AuditEntry.Outcome.valueOf(d.getString("outcome")),
                    d.getInteger("resultCount"),
                    d.getLong("durationMs")));
        }
        return views;
    }

    /** Espera as gravações pendentes no desligamento (o Spring chama no shutdown gracioso). */
    @Override
    public void close() {
        writer.close();
    }

    String hash(String document) {
        try {
            var mac = Mac.getInstance("HmacSHA256");
            mac.init(hashKey);
            return HexFormat.of().formatHex(mac.doFinal(document.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 unavailable", e);
        }
    }

    private Document toDocument(AuditEntry entry) {
        return new Document("username", entry.username())
                .append("action", entry.action().name())
                .append("at", Date.from(entry.at()))
                .append("year", entry.key().year())
                .append("documentType", entry.key().type().name())
                .append("documentMasked", Documents.mask(entry.key().document()))
                .append("documentHash", hash(entry.key().document()))
                .append("outcome", entry.outcome().name())
                .append("resultCount", entry.resultCount())
                .append("durationMs", entry.durationMs())
                .append("requestId", entry.requestId());
    }
}
