package com.teste.vinculos.infrastructure.seed;

import com.mongodb.MongoBulkWriteException;
import com.mongodb.WriteConcern;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.InsertManyOptions;
import com.teste.vinculos.domain.CustomerKey;
import com.teste.vinculos.infrastructure.mongo.Fields;
import com.teste.vinculos.infrastructure.mongo.MongoSchema;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static com.teste.vinculos.infrastructure.seed.DataGenerator.RECORDS_PER_CUSTOMER;

/**
 * Carga em massa: N workers pegam faixas de clientes e fazem insertMany não ordenado.
 * O índice secundário é criado só no final (construir 1 vez é bem mais barato que manter durante 1 bilhão de inserts).
 * Idempotente: _id determinístico + duplicatas ignoradas, então pode ser reexecutada ou retomada com segurança.
 */
@Component
public class DataLoader {

    private static final Logger log = LogManager.getLogger(DataLoader.class);
    private static final int DUPLICATE_KEY = 11000;
    private static final long LOG_INTERVAL_NS = 10_000_000_000L;
    private static final InsertManyOptions UNORDERED = new InsertManyOptions().ordered(false);

    private final MongoSchema schema;
    private final MongoCollection<Document> collection;

    public DataLoader(MongoTemplate mongo, MongoSchema schema) {
        this.schema = schema;
        // w=1 na carga: por ser idempotente, uma reexecução recompõe qualquer lote não confirmado.
        this.collection = mongo.getCollection(Fields.COLLECTION).withWriteConcern(WriteConcern.W1);
    }

    /** @return quantidade de registros inseridos nesta execução (duplicatas não contam). */
    public long load(long totalRecords, long startCustomer, int batchSize, int workers) {
        schema.ensureCollection();
        long endCustomer = totalRecords / RECORDS_PER_CUSTOMER;
        int customersPerBatch = Math.max(1, batchSize / RECORDS_PER_CUSTOMER);
        var next = new AtomicLong(startCustomer);
        var inserted = new AtomicLong();
        var failed = new AtomicBoolean();
        var lastLog = new AtomicLong(System.nanoTime());
        long start = System.nanoTime();

        log.info("Load started: customers [{}, {}) = {} records, batchSize={}, workers={}",
                startCustomer, endCustomer, (endCustomer - startCustomer) * RECORDS_PER_CUSTOMER, batchSize, workers);
        logSamples(startCustomer, endCustomer);

        try (ExecutorService pool = Executors.newFixedThreadPool(workers)) {
            List<Future<?>> tasks = new ArrayList<>(workers);
            for (int w = 0; w < workers; w++) {
                tasks.add(pool.submit(() -> {
                    try {
                        long from;
                        while (!failed.get() && (from = next.getAndAdd(customersPerBatch)) < endCustomer) {
                            long to = Math.min(from + customersPerBatch, endCustomer);
                            var docs = new ArrayList<Document>((int) (to - from) * RECORDS_PER_CUSTOMER);
                            for (long customer = from; customer < to; customer++) {
                                DataGenerator.generate(customer, docs::add);
                            }
                            long total = inserted.addAndGet(insert(docs));
                            logProgress(total, start, lastLog);
                        }
                    } catch (RuntimeException e) {
                        failed.set(true);
                        throw e;
                    }
                    return null;
                }));
            }
            for (Future<?> task : tasks) {
                await(task);
            }
        }

        log.info("Insertion finished: {} records in {} s; creating index...", inserted.get(), secondsSince(start));
        schema.ensureIndex();
        long inCollection = collection.estimatedDocumentCount();
        long expected = endCustomer * RECORDS_PER_CUSTOMER;
        if (inCollection < expected) {
            log.warn("Collection has {} records, expected at least {}: rerun the load to complete it", inCollection, expected);
        }
        log.info("Load finished in {} s: {} inserted in this run, {} in the collection",
                secondsSince(start), inserted.get(), inCollection);
        return inserted.get();
    }

    private long insert(List<Document> docs) {
        try {
            collection.insertMany(docs, UNORDERED);
            return docs.size();
        } catch (MongoBulkWriteException e) {
            // Duplicata = registro já gravado por uma execução anterior. Qualquer outro erro interrompe a carga.
            boolean onlyDuplicates = e.getWriteErrors().stream().allMatch(error -> error.getCode() == DUPLICATE_KEY);
            if (!onlyDuplicates || e.getWriteConcernError() != null) {
                throw e;
            }
            return docs.size() - e.getWriteErrors().size();
        }
    }

    private static void await(Future<?> task) {
        try {
            task.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Load interrupted", e);
        } catch (ExecutionException e) {
            throw new IllegalStateException("Load failed", e.getCause());
        }
    }

    private static void logProgress(long total, long start, AtomicLong lastLog) {
        long now = System.nanoTime();
        long previous = lastLog.get();
        if (now - previous >= LOG_INTERVAL_NS && lastLog.compareAndSet(previous, now)) {
            long seconds = Math.max(1, secondsSince(start));
            log.info("Progress: {} records ({} records/s)", total, total / seconds);
        }
    }

    // Dados sintéticos: o documento é exibido por inteiro para facilitar testar os endpoints.
    private static void logSamples(long startCustomer, long endCustomer) {
        for (long customer = startCustomer; customer < Math.min(startCustomer + 3, endCustomer); customer++) {
            CustomerKey key = DataGenerator.key(customer);
            log.info("Sample: year={} documentType={} document={} companies={}",
                    key.year(), key.type(), key.document(), DataGenerator.companies(customer));
        }
    }

    private static long secondsSince(long startNs) {
        return (System.nanoTime() - startNs) / 1_000_000_000L;
    }
}
