package com.teste.vinculos.web;

import com.teste.vinculos.domain.CustomerKey;
import com.teste.vinculos.infrastructure.seed.DataGenerator;
import com.teste.vinculos.infrastructure.seed.DataLoader;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Teste de falha: replica set de 3 nós, requisições contínuas e o primário morto no meio (kill, como uma queda real).
 * Comprova que a API continua respondendo (lê de um secundário com read concern majority durante a eleição),
 * que nenhuma falha vira 500 e que tudo volta a 200 logo depois. Os nós usam a rede do host para que o driver,
 * fora do Docker, alcance cada membro pelo endereço do replica set; por isso o teste roda só no Linux (CI).
 */
@SpringBootTest(properties = {"spring.cache.type=none", "app.rate-limit.enabled=false"})
@AutoConfigureMockMvc
@EnabledOnOs(OS.LINUX)
@Testcontainers(disabledWithoutDocker = true)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ReplicaSetFailoverTest {

    private static final int CUSTOMERS = 400;
    private static final long RUN_MS = 30_000;
    private static final long KILL_AT_MS = 5_000;
    private static final long RECOVERY_LIMIT_MS = 15_000;

    private static final List<GenericContainer<?>> nodes = new ArrayList<>();
    private static int[] ports;

    @Autowired
    MockMvcTester mvc;

    @Autowired
    DataLoader loader;

    @DynamicPropertySource
    static void mongo(DynamicPropertyRegistry registry) throws Exception {
        startReplicaSet();
        String hosts = IntStream.of(ports).mapToObj(p -> "localhost:" + p).collect(Collectors.joining(","));
        registry.add("spring.mongodb.uri",
                () -> "mongodb://" + hosts + "/vinculos?replicaSet=rs0&serverSelectionTimeoutMS=5000&maxPoolSize=20");
    }

    @AfterAll
    static void stopReplicaSet() {
        nodes.forEach(GenericContainer::stop);
    }

    @Test
    void apiKeepsAnsweringWhenThePrimaryDies() throws Exception {
        loader.load(CUSTOMERS * DataGenerator.RECORDS_PER_CUSTOMER, 0, 500, 2);
        int primary = primaryIndex();
        var random = new SplittableRandom(7);
        var results = new ArrayList<long[]>();
        boolean killed = false;
        long start = System.nanoTime();

        for (long elapsed = 0; elapsed < RUN_MS; elapsed = (System.nanoTime() - start) / 1_000_000) {
            if (!killed && elapsed >= KILL_AT_MS) {
                DockerClientFactory.instance().client().killContainerCmd(nodes.get(primary).getContainerId()).exec();
                killed = true;
            }
            results.add(new long[]{elapsed, request(random.nextLong(CUSTOMERS))});
            Thread.sleep(20);
        }

        List<long[]> afterKill = results.stream().filter(r -> r[0] >= KILL_AT_MS).toList();
        List<long[]> failures = afterKill.stream().filter(r -> r[1] != 200).toList();
        long lastFailureMs = failures.isEmpty() ? 0 : failures.getLast()[0] - KILL_AT_MS;
        int newPrimary = primaryIndex();
        System.out.printf("FAILOVER: %d requests (%d after the primary died), %d not 200 %s, last failure %d ms after the kill, "
                        + "primary node %d -> %d%n", results.size(), afterKill.size(), failures.size(),
                failures.stream().map(r -> String.valueOf(r[1])).distinct().toList(), lastFailureMs, primary, newPrimary);

        assertThat(results).allSatisfy(r -> assertThat(r[1]).as("status").isIn(200L, 503L));
        assertThat(afterKill).filteredOn(r -> r[0] >= KILL_AT_MS + RECOVERY_LIMIT_MS)
                .isNotEmpty()
                .allSatisfy(r -> assertThat(r[1]).as("status %d ms after the kill", r[0] - KILL_AT_MS).isEqualTo(200L));
        assertThat(newPrimary).isNotEqualTo(primary);
    }

    private long request(long customer) {
        CustomerKey key = DataGenerator.key(customer);
        String body = "{\"year\":%d,\"documentType\":\"%s\",\"document\":\"%s\"}".formatted(key.year(), key.type(), key.document());
        return mvc.post().uri("/api/v1/customers/companies").contentType(MediaType.APPLICATION_JSON).content(body)
                .exchange().getResponse().getStatus();
    }

    private static void startReplicaSet() throws Exception {
        ports = freePorts();
        for (int port : ports) {
            GenericContainer<?> node = new GenericContainer<>(DockerImageName.parse("mongo:8.0"))
                    .withNetworkMode("host")
                    .withCommand("--replSet", "rs0", "--bind_ip_all", "--port", String.valueOf(port))
                    .waitingFor(Wait.forLogMessage(".*Waiting for connections.*", 1).withStartupTimeout(Duration.ofSeconds(90)));
            node.start();
            nodes.add(node);
        }
        String members = IntStream.range(0, ports.length)
                .mapToObj(i -> "{_id: %d, host: 'localhost:%d', priority: %d}".formatted(i, ports[i], i == 0 ? 2 : 1))
                .collect(Collectors.joining(","));
        eval(0, "rs.initiate({_id: 'rs0', members: [" + members + "], settings: {electionTimeoutMillis: 2000, heartbeatIntervalMillis: 500}})");
        awaitHealthyReplicaSet();
    }

    // Primário eleito e os outros dois como secundários: só então a leitura majority é possível.
    private static void awaitHealthyReplicaSet() throws Exception {
        for (int i = 0; i < 120; i++) {
            if ("true".equals(eval(0, "rs.status().members.filter(m => m.state === 1).length === 1 "
                    + "&& rs.status().members.filter(m => m.state === 2).length === 2"))) {
                return;
            }
            Thread.sleep(500);
        }
        throw new IllegalStateException("replica set did not become healthy");
    }

    // Índice (na lista de nós) do primário atual, consultado num nó vivo; espera a eleição terminar.
    private static int primaryIndex() throws Exception {
        for (int attempt = 0; attempt < 60; attempt++) {
            for (int i = 0; i < nodes.size(); i++) {
                if (nodes.get(i).isRunning() && "true".equals(eval(i, "db.hello().isWritablePrimary"))) {
                    return i;
                }
            }
            Thread.sleep(500);
        }
        throw new IllegalStateException("no primary elected");
    }

    private static String eval(int node, String script) throws Exception {
        return nodes.get(node).execInContainer("mongosh", "--port", String.valueOf(ports[node]), "--quiet", "--eval", script)
                .getStdout().trim();
    }

    private static int[] freePorts() throws IOException {
        int[] free = new int[3];
        for (int i = 0; i < free.length; i++) {
            try (var socket = new ServerSocket(0)) {
                free[i] = socket.getLocalPort();
            }
        }
        return free;
    }
}
