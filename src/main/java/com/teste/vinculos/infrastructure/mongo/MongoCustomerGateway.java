package com.teste.vinculos.infrastructure.mongo;

import com.mongodb.ReadConcern;
import com.mongodb.ReadPreference;
import com.mongodb.WriteConcern;
import com.mongodb.client.MongoCollection;
import com.teste.vinculos.domain.CustomerGateway;
import com.teste.vinculos.domain.CustomerKey;
import com.teste.vinculos.domain.CustomerRecord;
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
import java.util.List;

import static java.util.concurrent.TimeUnit.MILLISECONDS;

/**
 * Acesso direto ao driver (sem mapeamento de entidades) para minimizar alocação por consulta.
 * Cache Caffeine com sync=true: requisições simultâneas da mesma chave disparam uma única ida ao banco.
 */
@Component
public class MongoCustomerGateway implements CustomerGateway {

    private static final Logger log = LogManager.getLogger(MongoCustomerGateway.class);

    private static final Document PROJECTION = new Document(Fields.ID, 1)
            .append(Fields.COMPANY, 1)
            .append(Fields.PRODUCT, 1)
            .append(Fields.AMOUNT_CENTS, 1)
            .append(Fields.UPDATED_AT, 1);

    private static final Comparator<CustomerRecord> ORDER =
            Comparator.comparing(CustomerRecord::company).thenComparingLong(CustomerRecord::id);

    private final MongoCollection<Document> collection;
    private final long timeoutMs;
    private final long slowMs;

    public MongoCustomerGateway(MongoTemplate mongo,
                                @Value("${app.query.timeout-ms:2000}") long timeoutMs,
                                @Value("${app.query.slow-ms:200}") long slowMs) {
        // Leitura no primário com read concern majority: só devolve dado confirmado pela maioria
        // do replica set (não sofre rollback), independente do que vier na connection string.
        this.collection = mongo.getCollection(Fields.COLLECTION)
                .withReadPreference(ReadPreference.primary())
                .withReadConcern(ReadConcern.MAJORITY)
                .withWriteConcern(WriteConcern.MAJORITY);
        this.timeoutMs = timeoutMs;
        this.slowMs = slowMs;
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
            log.warn("Slow {} query: {} ms for {}", query, ms, key);
        } else {
            log.debug("{} query: {} ms for {}", query, ms, key);
        }
    }
}
