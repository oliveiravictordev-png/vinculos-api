package com.teste.vinculos.benchmark;

import com.teste.vinculos.domain.CustomerKey;
import com.teste.vinculos.infrastructure.seed.DataGenerator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.SplittableRandom;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Mede a latência dos dois endpoints contra uma API já rodando com a base carregada. Só roda quando
 * bench.url é informado: mvn test -Dtest=ApiBenchmark -Dbench.url=http://localhost:8080
 *
 * <p>As chaves são sorteadas entre os clientes carregados (bench.customers), então todas existem na base.
 * "Sem cache" = primeira consulta de cada chave na API (o cache do MongoDB pode estar quente).
 */
@EnabledIfSystemProperty(named = "bench.url", matches = ".+")
class ApiBenchmark {

    private static final String BASE_URL = System.getProperty("bench.url");
    // Faixa de clientes carregados: [FIRST_CUSTOMER, FIRST_CUSTOMER + CUSTOMERS). Na VPS: 140 mi + 20 mi.
    private static final long FIRST_CUSTOMER = Long.getLong("bench.first-customer", 0L);
    private static final long CUSTOMERS = Long.getLong("bench.customers", 200_000_000L);
    private static final int SAMPLES = Integer.getInteger("bench.samples", 2_000);
    private static final int CONCURRENCY = Integer.getInteger("bench.concurrency", 64);
    private static final int THROUGHPUT_REQUESTS = Integer.getInteger("bench.requests", 50_000);

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @Test
    void measuresLatencyAndThroughput() throws Exception {
        var random = new SplittableRandom(42);
        List<Long> customers = random.longs(SAMPLES, FIRST_CUSTOMER, FIRST_CUSTOMER + CUSTOMERS).boxed().distinct().toList();

        // Aquecimento da JVM da API com chaves fora da amostra.
        random.longs(200, FIRST_CUSTOMER, FIRST_CUSTOMER + CUSTOMERS).forEach(c -> call("/companies", companiesBody(c)));

        long[] companiesCold = measure(customers, c -> call("/companies", companiesBody(c)));
        long[] companiesWarm = measure(customers, c -> call("/companies", companiesBody(c)));
        long[] recordsCold = measure(customers, c -> call("/records", recordsBody(c)));
        long[] recordsWarm = measure(customers, c -> call("/records", recordsBody(c)));

        System.out.println();
        System.out.println("| endpoint | cache da API | p50 | p95 | p99 | máx |");
        System.out.println("|---|---|---|---|---|---|");
        print("1 (companies)", "sem cache", companiesCold);
        print("1 (companies)", "com cache", companiesWarm);
        print("2 (records)", "sem cache", recordsCold);
        print("2 (records)", "com cache", recordsWarm);

        throughput(random);
    }

    // Vazão com chaves novas a cada requisição (quase sempre sem cache), N requisições simultâneas.
    private void throughput(SplittableRandom random) throws Exception {
        long[] keys = random.longs(THROUGHPUT_REQUESTS, FIRST_CUSTOMER, FIRST_CUSTOMER + CUSTOMERS).toArray();
        var next = new AtomicInteger();
        var errors = new AtomicInteger();
        long start = System.nanoTime();
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int t = 0; t < CONCURRENCY; t++) {
                pool.submit(() -> {
                    int i;
                    while ((i = next.getAndIncrement()) < keys.length) {
                        try {
                            call("/companies", companiesBody(keys[i]));
                        } catch (RuntimeException | AssertionError e) {
                            errors.incrementAndGet();
                        }
                    }
                });
            }
        }
        double seconds = (System.nanoTime() - start) / 1e9;
        System.out.printf("%nVazão do endpoint 1 (chaves novas, %d simultâneas): %,.0f req/s (%,d requisições em %.1f s, %d erros)%n",
                CONCURRENCY, keys.length / seconds, keys.length, seconds, errors.get());
        assertThat(errors.get()).isZero();
    }

    private long[] measure(List<Long> customers, java.util.function.LongConsumer request) {
        long[] nanos = new long[customers.size()];
        for (int i = 0; i < nanos.length; i++) {
            long start = System.nanoTime();
            request.accept(customers.get(i));
            nanos[i] = System.nanoTime() - start;
        }
        Arrays.sort(nanos);
        return nanos;
    }

    private void call(String path, String body) {
        try {
            var request = HttpRequest.newBuilder(URI.create(BASE_URL + "/api/v1/customers" + path))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
            assertThat(response.body()).contains("\"companies\":[").doesNotContain("\"companies\":[]");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String companiesBody(long customer) {
        CustomerKey key = DataGenerator.key(customer);
        return "{\"year\":%d,\"documentType\":\"%s\",\"document\":\"%s\"}"
                .formatted(key.year(), key.type(), key.document());
    }

    private static String recordsBody(long customer) {
        String companies = DataGenerator.companies(customer).stream()
                .map(c -> "\"" + c + "\"")
                .collect(Collectors.joining(",", "[", "]"));
        String base = companiesBody(customer);
        return base.substring(0, base.length() - 1) + ",\"companies\":" + companies + "}";
    }

    private static void print(String endpoint, String cache, long[] sorted) {
        System.out.printf("| %s | %s | %s | %s | %s | %s |%n", endpoint, cache,
                ms(percentile(sorted, 50)), ms(percentile(sorted, 95)), ms(percentile(sorted, 99)), ms(sorted[sorted.length - 1]));
    }

    private static long percentile(long[] sorted, double p) {
        int index = (int) Math.ceil(p / 100 * sorted.length) - 1;
        return sorted[Math.max(0, Math.min(index, sorted.length - 1))];
    }

    private static String ms(long nanos) {
        return String.format("%.1f ms", nanos / 1e6);
    }
}
