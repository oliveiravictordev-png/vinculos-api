package com.teste.vinculos.infrastructure.mongo;

import com.mongodb.ReadConcern;
import com.mongodb.ReadPreference;
import com.mongodb.WriteConcern;
import com.mongodb.client.MongoCollection;
import com.teste.vinculos.domain.CustomerGateway;
import com.teste.vinculos.domain.CustomerKey;
import com.teste.vinculos.domain.CustomerRecord;
import com.teste.vinculos.domain.RecordCursor;
import com.teste.vinculos.domain.RecordFilter;
import com.teste.vinculos.domain.RecordPage;
import com.teste.vinculos.domain.RecordSearchGateway;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bson.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.regex.Pattern;

import static java.util.concurrent.TimeUnit.MILLISECONDS;

/**
 * Acesso direto ao driver (sem mapeamento de entidades) para minimizar alocação por consulta.
 * Cache Caffeine com sync=true: requisições simultâneas da mesma chave disparam uma única ida ao banco.
 */
@Component
public class MongoCustomerGateway implements CustomerGateway, RecordSearchGateway {

    private static final Logger log = LogManager.getLogger(MongoCustomerGateway.class);

    private static final Document PROJECTION = new Document(Fields.ID, 1)
            .append(Fields.COMPANY, 1)
            .append(Fields.PRODUCT, 1)
            .append(Fields.AMOUNT_CENTS, 1)
            .append(Fields.UPDATED_AT, 1);

    private static final Comparator<CustomerRecord> ORDER =
            Comparator.comparing(CustomerRecord::company).thenComparingLong(CustomerRecord::id);

    /** Contador de consultas acima de {@code app.query.slow-ms}, lido pelo monitor de alertas. */
    public static final String SLOW_QUERIES = "vinculos.query.slow";

    private final MongoCollection<Document> collection;
    private final long timeoutMs;
    private final long slowMs;
    private final Counter slowQueries;

    public MongoCustomerGateway(MongoTemplate mongo, MeterRegistry metrics,
                                @Value("${app.query.timeout-ms:2000}") long timeoutMs,
                                @Value("${app.query.slow-ms:200}") long slowMs) {
        // Read concern majority: só devolve dado confirmado pela maioria do replica set (não sofre rollback).
        // primaryPreferred: lê do primário e, se ele cair, de um secundário enquanto ocorre a eleição; como a leitura
        // é majority, o secundário também só devolve dado confirmado. Fixado no código, não na connection string.
        this.collection = mongo.getCollection(Fields.COLLECTION)
                .withReadPreference(ReadPreference.primaryPreferred())
                .withReadConcern(ReadConcern.MAJORITY)
                .withWriteConcern(WriteConcern.MAJORITY);
        this.timeoutMs = timeoutMs;
        this.slowMs = slowMs;
        this.slowQueries = Counter.builder(SLOW_QUERIES)
                .description("MongoDB queries slower than app.query.slow-ms")
                .register(metrics);
    }

    @Override
    @Cacheable(cacheNames = "companies", sync = true)
    public List<String> findCompanies(CustomerKey key) {
        long start = System.nanoTime();
        List<String> companies = collection.distinct(Fields.COMPANY, filter(key), String.class)
                .maxTime(timeoutMs, MILLISECONDS)
                .into(new ArrayList<>());
        companies.sort(null);
        logElapsed("companies", key, start);
        return List.copyOf(companies);
    }

    @Override
    @Cacheable(cacheNames = "records", sync = true)
    public List<CustomerRecord> findRecords(CustomerKey key, List<String> companies) {
        long start = System.nanoTime();
        Document filter = filter(key).append(Fields.COMPANY, new Document("$in", companies));
        var records = new ArrayList<CustomerRecord>();
        for (Document d : collection.find(filter).projection(PROJECTION).maxTime(timeoutMs, MILLISECONDS)) {
            records.add(toRecord(d));
        }
        records.sort(ORDER);
        logElapsed("records", key, start);
        return List.copyOf(records);
    }

    // Sem cache: com filtros e cursor as combinações são muitas e o acerto seria baixo. A chave do cliente vem
    // primeiro no filtro, então o índice {a, t, v, e} limita a varredura aos poucos registros dela; produto,
    // período e cursor são conferidos nesses documentos.
    @Override
    public List<CustomerRecord> search(RecordFilter filter, int limit) {
        long start = System.nanoTime();
        Document query = searchFilter(filter);
        RecordCursor cursor = filter.cursor();
        if (cursor != null) {
            query.append("$or", List.of(
                    new Document(Fields.COMPANY, new Document("$gt", cursor.company())),
                    new Document(Fields.COMPANY, cursor.company()).append(Fields.ID, new Document("$gt", cursor.id()))));
        }
        var records = new ArrayList<CustomerRecord>();
        for (Document d : collection.find(query).projection(PROJECTION)
                .sort(new Document(Fields.COMPANY, 1).append(Fields.ID, 1))
                .limit(limit).maxTime(timeoutMs, MILLISECONDS)) {
            records.add(toRecord(d));
        }
        logElapsed("search", filter.key(), start);
        return List.copyOf(records);
    }

    @Override
    public RecordPage.Totals totals(RecordFilter filter) {
        long start = System.nanoTime();
        Document totals = collection.aggregate(List.of(
                        new Document("$match", searchFilter(filter)),
                        new Document("$group", new Document("_id", null)
                                .append("records", new Document("$sum", 1L))
                                .append("cents", new Document("$sum", "$" + Fields.AMOUNT_CENTS))
                                .append("companies", new Document("$addToSet", "$" + Fields.COMPANY)))))
                .maxTime(timeoutMs, MILLISECONDS)
                .first();
        logElapsed("totals", filter.key(), start);
        if (totals == null) {
            return RecordPage.Totals.EMPTY;
        }
        return new RecordPage.Totals(totals.get("records", Number.class).longValue(),
                totals.getList("companies", String.class).size(),
                BigDecimal.valueOf(totals.get("cents", Number.class).longValue(), 2));
    }

    private static Document searchFilter(RecordFilter filter) {
        Document query = filter(filter.key());
        if (!filter.companies().isEmpty()) {
            query.append(Fields.COMPANY, new Document("$in", filter.companies()));
        }
        if (filter.product() != null) {
            // Pattern.quote: o texto do usuário é literal, nunca uma expressão regular (evita ReDoS).
            query.append(Fields.PRODUCT, new Document("$regex", Pattern.quote(filter.product())).append("$options", "i"));
        }
        if (filter.updatedFrom() != null || filter.updatedTo() != null) {
            var range = new Document();
            if (filter.updatedFrom() != null) {
                range.append("$gte", Date.from(filter.updatedFrom()));
            }
            if (filter.updatedTo() != null) {
                range.append("$lte", Date.from(filter.updatedTo()));
            }
            query.append(Fields.UPDATED_AT, range);
        }
        return query;
    }

    private static Document filter(CustomerKey key) {
        return new Document(Fields.YEAR, key.year())
                .append(Fields.TYPE, key.type().name())
                .append(Fields.DOCUMENT, key.document());
    }

    // Valor gravado em centavos (long): sem erro de arredondamento de ponto flutuante.
    private static CustomerRecord toRecord(Document d) {
        return new CustomerRecord(
                d.getLong(Fields.ID),
                d.getString(Fields.COMPANY),
                d.getString(Fields.PRODUCT),
                BigDecimal.valueOf(d.getLong(Fields.AMOUNT_CENTS), 2),
                d.getDate(Fields.UPDATED_AT).toInstant());
    }

    private void logElapsed(String query, CustomerKey key, long start) {
        long ms = (System.nanoTime() - start) / 1_000_000;
        if (ms >= slowMs) {
            slowQueries.increment();
            log.warn("Slow {} query: {} ms for {}", query, ms, key);
        } else {
            log.debug("{} query: {} ms for {}", query, ms, key);
        }
    }
}
