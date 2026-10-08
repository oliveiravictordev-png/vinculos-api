package com.teste.vinculos.web.security;

import com.teste.vinculos.application.FindCompaniesUseCase;
import com.teste.vinculos.application.FindRecordsByCompanyUseCase;
import com.teste.vinculos.web.CustomerController;
import com.teste.vinculos.web.RateLimitProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.cache.CacheManager;
import org.springframework.cache.support.NoOpCacheManager;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest({AuthController.class, CustomerController.class})
@Import({SecurityConfig.class, TokenService.class, SecurityIntegrationTest.NoCache.class})
@EnableConfigurationProperties({AuthProperties.class, RateLimitProperties.class})
@TestPropertySource(properties = {
        "app.auth.admin-1-username=test-admin-1",
        "app.auth.admin-1-password=test-password-1",
        "app.auth.admin-2-username=test-admin-2",
        "app.auth.admin-2-password=test-password-2",
        "app.auth.jwt-secret=01234567890123456789012345678901",
        "app.auth.issuer=vinculos-api-test",
        "app.auth.ttl=PT5M"
})
class SecurityIntegrationTest {

    @TestConfiguration
    static class NoCache {
        @Bean
        CacheManager cacheManager() {
            return new NoOpCacheManager();
        }
    }

    private static final String CUSTOMER_REQUEST = """
            {"year":2026,"documentType":"CPF","document":"05685862717"}
            """;

    @Autowired
    MockMvc mvc;

    @MockitoBean
    FindCompaniesUseCase findCompanies;

    @MockitoBean
    FindRecordsByCompanyUseCase findRecords;

    @Test
    void issuesTokenAndAcceptsItOnProtectedEndpoint() throws Exception {
        String response = mvc.perform(post("/api/v1/auth/token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"test-admin-1\",\"password\":\"test-password-1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andReturn().getResponse().getContentAsString();

        String token = response.replaceAll(".*\\\"accessToken\\\":\\\"([^\\\"]+)\\\".*", "$1");
        mvc.perform(post("/api/v1/customers/companies")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CUSTOMER_REQUEST))
                .andExpect(status().isOk());
    }

    @Test
    void rejectsProtectedEndpointWithoutToken() throws Exception {
        mvc.perform(post("/api/v1/customers/companies")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CUSTOMER_REQUEST))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void secondAdministratorCanAlsoAuthenticate() throws Exception {
        mvc.perform(post("/api/v1/auth/token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"test-admin-2\",\"password\":\"test-password-2\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty());
    }

    @Test
    void rejectsWrongPassword() throws Exception {
        mvc.perform(post("/api/v1/auth/token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"test-admin-1\",\"password\":\"wrong\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.detail").value("Invalid username or password"));
    }

    @Test
    void rejectsInvalidBearerToken() throws Exception {
        mvc.perform(post("/api/v1/customers/companies")
                        .header("Authorization", "Bearer invalid-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CUSTOMER_REQUEST))
                .andExpect(status().isUnauthorized());
    }
}
