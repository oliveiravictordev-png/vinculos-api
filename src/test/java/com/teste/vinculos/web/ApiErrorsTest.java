package com.teste.vinculos.web;

import com.teste.vinculos.application.FindCompaniesUseCase;
import com.teste.vinculos.application.FindRecordsByCompanyUseCase;
import com.teste.vinculos.domain.CustomerGateway;
import com.teste.vinculos.domain.CustomerKey;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.cache.CacheManager;
import org.springframework.cache.support.NoOpCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

/** Formato e status das respostas de erro: a API nunca revela rotas com 404/405/415 nem detalhes internos. */
@WebMvcTest(CustomerController.class)
@EnableConfigurationProperties(RateLimitProperties.class)
class ApiErrorsTest {

    private static final String VALID_KEY = """
            {"year": 2026, "documentType": "CPF", "document": "056.858.627-17"}""";

    @Autowired
    MockMvcTester mvc;

    @MockitoBean
    FindCompaniesUseCase findCompanies;

    @MockitoBean
    FindRecordsByCompanyUseCase findRecords;

    @MockitoBean
    CustomerGateway gateway;

    @Test
    void validRequestStillWorks() {
        given(findCompanies.execute(any(CustomerKey.class))).willReturn(List.of("10007037000103"));

        assertThat(post("/api/v1/customers/companies", VALID_KEY)).hasStatusOk()
                .bodyJson().extractingPath("$.companies[0]").isEqualTo("10007037000103");
    }

    @Test
    void unknownPathIsA400NotA404() {
        assertInvalidRequest(post("/api/v1/customers/unknown", VALID_KEY));
        assertInvalidRequest(mvc.get().uri("/qualquer/coisa").exchange());
    }

    @Test
    void wrongMethodIsA400NotA405() {
        assertInvalidRequest(mvc.get().uri("/api/v1/customers/companies").exchange());
    }

    @Test
    void malformedJsonAndWrongContentTypeAreA400() {
        assertInvalidRequest(post("/api/v1/customers/companies", "{\"year\":"));
        assertInvalidRequest(mvc.post().uri("/api/v1/customers/companies")
                .contentType(MediaType.TEXT_PLAIN).content(VALID_KEY).exchange());
    }

    @Test
    void documentLongerThanAFormattedCnpjIsRejected() {
        MvcTestResult result = post("/api/v1/customers/companies", """
                {"year": 2026, "documentType": "CPF", "document": "056.858.627-170000000"}""");

        assertThat(result).hasStatus(HttpStatus.BAD_REQUEST)
                .bodyJson().extractingPath("$.detail").isEqualTo("document must have at most 18 characters");
    }

    @Test
    void unexpectedErrorsDoNotLeakDetails() {
        given(findCompanies.execute(any(CustomerKey.class))).willThrow(new IllegalStateException("internal secret"));

        MvcTestResult result = post("/api/v1/customers/companies", VALID_KEY);

        assertThat(result).hasStatus(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(result).bodyText().doesNotContain("internal secret").doesNotContain("IllegalStateException");
    }

    // A aplicação usa @EnableCaching; nestes testes o cache não importa.
    @TestConfiguration
    static class NoCache {
        @Bean
        CacheManager cacheManager() {
            return new NoOpCacheManager();
        }
    }

    private MvcTestResult post(String uri, String body) {
        return mvc.post().uri(uri).contentType(MediaType.APPLICATION_JSON).content(body).exchange();
    }

    private static void assertInvalidRequest(MvcTestResult result) {
        assertThat(result).hasStatus(HttpStatus.BAD_REQUEST)
                .hasContentType("application/problem+json")
                .bodyJson().extractingPath("$.detail").isEqualTo(ApiExceptionHandler.INVALID_REQUEST);
    }
}
