package com.teste.vinculos.web;

import com.teste.vinculos.domain.CustomerKey;
import com.teste.vinculos.infrastructure.mongo.Fields;
import com.teste.vinculos.infrastructure.seed.DataGenerator;
import com.teste.vinculos.infrastructure.seed.DataLoader;
import com.teste.vinculos.web.dto.RecordsResponse;
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

import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/** Sobe a API contra um MongoDB real (replica set via Testcontainers). Ignorado se não houver Docker. */
@SpringBootTest(properties = {
        "app.auth.admin-1-username=test-admin-1",
        "app.auth.admin-1-password=test-password-1",
        "app.auth.admin-2-username=test-admin-2",
        "app.auth.admin-2-password=test-password-2",
        "app.auth.jwt-secret=01234567890123456789012345678901",
        "app.auth.issuer=vinculos-api-test",
        "app.auth.ttl=PT5M"
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

    @BeforeAll
    void load() {
        loader.load(TOTAL_RECORDS, 0, 100, 2);
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

    private static String json(CustomerKey key, List<String> companies) {
        String base = "\"year\": %d, \"documentType\": \"%s\", \"document\": \"%s\""
                .formatted(key.year(), key.type(), key.document());
        if (companies == null) {
            return "{" + base + "}";
        }
        String list = companies.stream().map(c -> "\"" + c + "\"").collect(Collectors.joining(",", "[", "]"));
        return "{" + base + ", \"companies\": " + list + "}";
    }
}
