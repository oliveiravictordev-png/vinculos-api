package com.teste.vinculos.web;

import com.teste.vinculos.domain.CustomerKey;
import com.teste.vinculos.domain.Documents;
import com.teste.vinculos.domain.LoginAttemptStore;
import com.teste.vinculos.domain.SessionStore;
import com.teste.vinculos.infrastructure.mongo.Fields;
import com.teste.vinculos.infrastructure.mongo.MongoQueryAuditLog;
import com.teste.vinculos.infrastructure.mongo.MongoSchema;
import com.teste.vinculos.infrastructure.seed.DataGenerator;
import com.teste.vinculos.infrastructure.seed.DataLoader;
import com.teste.vinculos.support.TestKeys;
import com.teste.vinculos.web.dto.RecordsResponse;
import com.teste.vinculos.web.dto.SearchResponse;
import org.bson.Document;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.cache.CacheManager;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mongodb.MongoDBContainer;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/** Sobe a API contra um MongoDB real (replica set via Testcontainers). Ignorado se não houver Docker. */
@SpringBootTest(properties = {
        "app.auth.admin-1-username=test-admin-1",
        "app.auth.admin-1-password=test-password-1",
        "app.auth.admin-2-username=test-admin-2",
        "app.auth.admin-2-password=test-password-2",
        "app.auth.issuer=vinculos-api-test",
        "app.auth.ttl=PT5M",
        "app.audit.hash-secret=test-audit-secret-0123456789abcdef"
})
@AutoConfigureMockMvc(addFilters = false)
@Testcontainers(disabledWithoutDocker = true)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CustomerApiTest {

    private static final long TOTAL_RECORDS = 1_000;
    private static final String UNLINKED_COMPANY = "11222333000181";

    @Container
    @ServiceConnection
    static MongoDBContainer mongo = new MongoDBContainer("mongo:8.0");

    @Autowired
    MockMvcTester mvc;

    @Autowired
    DataLoader loader;

    @Autowired
    CacheManager cacheManager;

    @Autowired
    MongoTemplate mongoTemplate;

    @Autowired
    SessionStore sessions;

    @Autowired
    LoginAttemptStore loginAttempts;

    @DynamicPropertySource
    static void keys(DynamicPropertyRegistry registry) {
        registry.add("app.auth.jwt-private-key", TestKeys::privateKey);
    }

    @BeforeAll
    void load() {
        loader.load(TOTAL_RECORDS, 0, 100, 2);
    }

    @Test
    void searchPagesThroughAllRecordsWithTotalsOfTheWholeFilter() {
        long customer = 7;
        CustomerKey key = DataGenerator.key(customer);
        var ids = new ArrayList<Long>();
        String cursor = null;
        do {
            String body = "{" + keyJson(key) + ", \"limit\": 2" + (cursor == null ? "" : ", \"cursor\": \"" + cursor + "\"") + "}";
            var next = new AtomicReference<String>();
            assertThat(mvc.post().uri("/api/v1/customers/search").contentType(MediaType.APPLICATION_JSON).content(body))
                    .hasStatusOk().bodyJson().convertTo(SearchResponse.class).satisfies(page -> {
                        page.items().forEach(item -> ids.add(item.id()));
                        assertThat(page.totals().records()).isEqualTo(DataGenerator.RECORDS_PER_CUSTOMER);
                        assertThat(page.totals().companies()).isEqualTo(DataGenerator.companies(customer).size());
                        next.set(page.nextCursor());
                    });
            cursor = next.get();
        } while (cursor != null);

        assertThat(ids).hasSize(DataGenerator.RECORDS_PER_CUSTOMER).doesNotHaveDuplicates();
    }

    @Test
    void searchFiltersByProductAndPeriodUsingTheIndex() {
        long customer = 11;
        CustomerKey key = DataGenerator.key(customer);
        var all = new ArrayList<Document>();
        DataGenerator.generate(customer, all::add);
        String product = all.getFirst().getString(Fields.PRODUCT);
        long expected = all.stream().filter(d -> d.getString(Fields.PRODUCT).equals(product)).count();

        assertThat(mvc.post().uri("/api/v1/customers/search").contentType(MediaType.APPLICATION_JSON)
                .content("{" + keyJson(key) + ", \"product\": \"" + product.toLowerCase().substring(0, 6) + "\"}"))
                .hasStatusOk().bodyJson().extractingPath("$.items").asArray().hasSizeGreaterThanOrEqualTo((int) expected);
        assertThat(mvc.post().uri("/api/v1/customers/search").contentType(MediaType.APPLICATION_JSON)
                .content("{" + keyJson(key) + ", \"updatedFrom\": \"2999-01-01T00:00:00Z\"}"))
                .hasStatusOk().bodyJson().extractingPath("$.totals.records").isEqualTo(0);
        String company = DataGenerator.companies(customer).getFirst();
        long inCompany = all.stream().filter(d -> d.getString(Fields.COMPANY).equals(company)).count();
        assertThat(mvc.post().uri("/api/v1/customers/search").contentType(MediaType.APPLICATION_JSON)
                .content("{" + keyJson(key) + ", \"companies\": [\"" + company + "\"], "
                        + "\"updatedFrom\": \"2000-01-01T00:00:00Z\", \"updatedTo\": \"2999-01-01T00:00:00Z\"}"))
                .hasStatusOk().bodyJson().extractingPath("$.totals.records").isEqualTo((int) inCompany);

        Document plan = mongoTemplate.getCollection(Fields.COLLECTION)
                .find(new Document(Fields.YEAR, key.year()).append(Fields.TYPE, key.type().name())
                        .append(Fields.DOCUMENT, key.document()).append(Fields.PRODUCT, product))
                .sort(new Document(Fields.COMPANY, 1).append(Fields.ID, 1))
                .explain();
        assertThat(plan.toJson()).contains(MongoSchema.INDEX);
    }

    @Test
    void queriesAreAuditedWithMaskedAndHashedDocumentOnly() throws InterruptedException {
        CustomerKey key = DataGenerator.key(13);
        mvc.post().uri("/api/v1/customers/companies").contentType(MediaType.APPLICATION_JSON)
                .content(json(key, null)).exchange();

        var audits = mongoTemplate.getCollection(MongoQueryAuditLog.COLLECTION);
        Document entry = await(() -> audits.find(new Document("documentMasked", Documents.mask(key.document()))).first());
        assertThat(entry.getString("action")).isEqualTo("COMPANIES");
        assertThat(entry.getString("outcome")).isEqualTo("SUCCESS");
        assertThat(entry.getString("documentHash")).hasSize(64);
        assertThat(entry.toJson()).doesNotContain(key.document());

        assertThat(mvc.get().uri("/api/v1/audit/history?limit=5"))
                .hasStatusOk().bodyJson().extractingPath("$.items").asArray().isNotEmpty();
    }

    @Test
    void loginFailuresAreCountedAtomicallyAndLockTheUser() {
        Instant forget = Instant.now().plusSeconds(3600);

        assertThat(loginAttempts.recordFailure("lock-test", forget)).isEqualTo(1);
        assertThat(loginAttempts.recordFailure("lock-test", forget)).isEqualTo(2);
        assertThat(loginAttempts.lockedUntil("lock-test")).isNull();

        Instant until = Instant.now().plusSeconds(60).truncatedTo(ChronoUnit.MILLIS);
        loginAttempts.lockUntil("lock-test", until);
        assertThat(loginAttempts.lockedUntil("lock-test")).isEqualTo(until);

        loginAttempts.clear("lock-test");
        assertThat(loginAttempts.lockedUntil("lock-test")).isNull();
        assertThat(loginAttempts.recordFailure("lock-test", forget)).isEqualTo(1);
    }

    @Test
    void sessionsAreSharedAndRevocable() {
        String id = sessions.create("test-admin-1", Instant.now().plusSeconds(60));

        assertThat(sessions.isActive(id, "test-admin-1")).isTrue();
        assertThat(sessions.isActive(id, "test-admin-2")).isFalse();
        sessions.revoke(id);
        assertThat(sessions.isActive(id, "test-admin-1")).isFalse();
        assertThat(sessions.isActive(sessions.create("test-admin-1", Instant.now().minusSeconds(1)), "test-admin-1")).isFalse();
    }

    @Test
    void endpoint1ReturnsCompaniesLinkedToCustomer() {
        long customer = 3;
        CustomerKey key = DataGenerator.key(customer);
        List<String> expected = DataGenerator.companies(customer).stream().sorted().toList();

        assertThat(mvc.post().uri("/api/v1/customers/companies")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(key, null)))
                .hasStatusOk()
                .bodyJson().extractingPath("$.companies").asArray().containsExactlyElementsOf(expected);

        assertThat(cacheManager.getCache("companies").get(key)).isNotNull();
    }

    @Test
    void endpoint2ReturnsRecordsByCompany() {
        long customer = 7;
        CustomerKey key = DataGenerator.key(customer);
        var companies = new ArrayList<>(DataGenerator.companies(customer));
        companies.add(UNLINKED_COMPANY);

        assertThat(mvc.post().uri("/api/v1/customers/records")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(key, companies)))
                .hasStatusOk()
                .bodyJson().convertTo(RecordsResponse.class)
                .satisfies(response -> {
                    assertThat(response.companies()).extracting(RecordsResponse.Company::company)
                            .containsExactlyElementsOf(new TreeSet<>(companies));
                    assertThat(response.companies().stream().mapToInt(c -> c.records().size()).sum())
                            .isEqualTo(DataGenerator.RECORDS_PER_CUSTOMER);
                    assertThat(response.companies())
                            .allSatisfy(c -> assertThat(c.records().isEmpty())
                                    .isEqualTo(c.company().equals(UNLINKED_COMPANY)));
                });
    }

    @Test
    void invalidDocumentReturns400() {
        assertThat(mvc.post().uri("/api/v1/customers/companies")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"year": 2026, "documentType": "CPF", "document": "12345678900"}
                        """))
                .hasStatus(HttpStatus.BAD_REQUEST);
    }

    @Test
    void reloadDoesNotDuplicateRecords() {
        assertThat(loader.load(TOTAL_RECORDS, 0, 100, 2)).isZero();
        assertThat(mongoTemplate.getCollection(Fields.COLLECTION).countDocuments()).isEqualTo(TOTAL_RECORDS);
    }

    // A auditoria é gravada em background: espera até 5 s.
    private static <T> T await(Supplier<T> lookup) throws InterruptedException {
        long deadline = System.nanoTime() + 5_000_000_000L;
        T value;
        while ((value = lookup.get()) == null && System.nanoTime() < deadline) {
            Thread.sleep(50);
        }
        assertThat(value).as("audit entry").isNotNull();
        return value;
    }

    private static String keyJson(CustomerKey key) {
        return "\"year\": %d, \"documentType\": \"%s\", \"document\": \"%s\""
                .formatted(key.year(), key.type(), key.document());
    }

    private static String json(CustomerKey key, List<String> companies) {
        String base = keyJson(key);
        if (companies == null) {
            return "{" + base + "}";
        }
        String list = companies.stream().map(c -> "\"" + c + "\"").collect(Collectors.joining(",", "[", "]"));
        return "{" + base + ", \"companies\": " + list + "}";
    }
}
