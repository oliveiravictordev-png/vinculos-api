package com.teste.vinculos.web.security;

import com.teste.vinculos.application.FindCompaniesUseCase;
import com.teste.vinculos.application.FindRecordsByCompanyUseCase;
import com.teste.vinculos.application.SearchRecordsUseCase;
import com.teste.vinculos.domain.SessionStore;
import com.teste.vinculos.support.InMemorySessionStore;
import com.teste.vinculos.support.TestKeys;
import com.teste.vinculos.support.WebSliceConfig;
import com.teste.vinculos.web.AuditController;
import com.teste.vinculos.web.CustomerController;
import com.teste.vinculos.web.RateLimitProperties;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Cadeia de segurança real (JWT RS256, cookies, escopos, revogação) sobre controllers com casos de uso falsos. */
@WebMvcTest({AuthController.class, CustomerController.class, AuditController.class})
@Import({SecurityConfig.class, TokenService.class, ActiveSessions.class, WebSliceConfig.class,
        SecurityIntegrationTest.Sessions.class})
@EnableConfigurationProperties({AuthProperties.class, RateLimitProperties.class})
@TestPropertySource(properties = {
        "app.auth.admin-1-username=test-admin-1",
        "app.auth.admin-1-password=test-password-1",
        "app.auth.admin-2-username=test-admin-2",
        "app.auth.admin-2-password=test-password-2",
        "app.auth.issuer=vinculos-api-test",
        "app.auth.ttl=PT5M",
        "app.auth.refresh-ttl=PT1H",
        "app.rate-limit.enabled=false"
})
class SecurityIntegrationTest {

    private static final String CUSTOMER_REQUEST = """
            {"year":2026,"documentType":"CPF","document":"05685862717"}
            """;
    private static final String ADMIN_1 = "{\"username\":\"test-admin-1\",\"password\":\"test-password-1\"}";

    @DynamicPropertySource
    static void keys(DynamicPropertyRegistry registry) {
        registry.add("app.auth.jwt-private-key", TestKeys::privateKey);
    }

    @TestConfiguration
    static class Sessions {
        @Bean
        SessionStore sessionStore() {
            return new InMemorySessionStore();
        }
    }

    @Autowired
    MockMvcTester mvc;

    @Autowired
    SessionStore store;

    @Autowired
    TokenService tokens;

    @MockitoBean
    FindCompaniesUseCase findCompanies;

    @MockitoBean
    FindRecordsByCompanyUseCase findRecords;

    @MockitoBean
    SearchRecordsUseCase search;

    @Test
    void issuesBearerTokenAndAcceptsItOnProtectedEndpoint() {
        String token = bearerToken(ADMIN_1);

        assertThat(companies("Authorization", "Bearer " + token)).hasStatusOk();
        assertThat(token.split("\\.")[0]).isNotBlank();
    }

    @Test
    void secondAdministratorCanAlsoAuthenticate() {
        assertThat(postJson("/api/v1/auth/token", "{\"username\":\"test-admin-2\",\"password\":\"test-password-2\"}"))
                .hasStatusOk().bodyJson().extractingPath("$.accessToken").isNotNull();
    }

    @Test
    void rejectsWrongPasswordWithoutRevealingWhichFieldIsWrong() {
        assertThat(postJson("/api/v1/auth/token", "{\"username\":\"test-admin-1\",\"password\":\"wrong\"}"))
                .hasStatus(HttpStatus.UNAUTHORIZED)
                .bodyJson().extractingPath("$.detail").isEqualTo("Invalid username or password");
        assertThat(postJson("/api/v1/auth/token", "{\"username\":\"nobody\",\"password\":\"wrong\"}"))
                .hasStatus(HttpStatus.UNAUTHORIZED)
                .bodyJson().extractingPath("$.detail").isEqualTo("Invalid username or password");
    }

    @Test
    void missingOrInvalidTokenIsA401ProblemDetail() {
        assertThat(mvc.post().uri("/api/v1/customers/companies").contentType(MediaType.APPLICATION_JSON)
                .content(CUSTOMER_REQUEST).exchange())
                .hasStatus(HttpStatus.UNAUTHORIZED).hasContentType("application/problem+json")
                .bodyJson().extractingPath("$.detail").isEqualTo("Authentication required");
        assertThat(companies("Authorization", "Bearer invalid-token")).hasStatus(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void tamperedTokenIsRejected() {
        String token = bearerToken(ADMIN_1);
        String[] parts = token.split("\\.");
        String forged = parts[0] + "." + parts[1] + "." + new StringBuilder(parts[2]).reverse();

        assertThat(companies("Authorization", "Bearer " + forged)).hasStatus(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void browserLoginUsesHttpOnlyCookiesAndNeverReturnsTheToken() {
        MvcTestResult login = postJson("/api/v1/auth/session", ADMIN_1);

        assertThat(login).hasStatusOk().bodyJson().extractingPath("$.username").isEqualTo("test-admin-1");
        assertThat(login).bodyText().doesNotContain("accessToken").doesNotContain(cookie(login, SessionCookies.ACCESS));
        List<String> setCookies = login.getResponse().getHeaders(HttpHeaders.SET_COOKIE);
        assertThat(setCookies).hasSize(2).allSatisfy(c -> assertThat(c).contains("HttpOnly", "Secure", "SameSite=Strict"));
        assertThat(setCookies).anySatisfy(c -> assertThat(c).startsWith(SessionCookies.REFRESH).contains("Path=/api/v1/auth"));

        Cookie access = new Cookie(SessionCookies.ACCESS, cookie(login, SessionCookies.ACCESS));
        assertThat(mvc.post().uri("/api/v1/customers/companies").cookie(access)
                .contentType(MediaType.APPLICATION_JSON).content(CUSTOMER_REQUEST).exchange()).hasStatusOk();
        assertThat(mvc.get().uri("/api/v1/auth/session").cookie(access).exchange())
                .hasStatusOk().bodyJson().extractingPath("$.scopes").asArray()
                .containsExactlyInAnyOrder("customers:read", "customers:export", "audit:read");
        assertThat(mvc.get().uri("/api/v1/audit/history").cookie(access).exchange()).hasStatusOk();
    }

    @Test
    void refreshRenewsTheSessionFromTheRefreshCookie() {
        MvcTestResult login = postJson("/api/v1/auth/session", ADMIN_1);
        Cookie refresh = new Cookie(SessionCookies.REFRESH, cookie(login, SessionCookies.REFRESH));

        MvcTestResult renewed = mvc.post().uri("/api/v1/auth/refresh").cookie(refresh).exchange();

        assertThat(renewed).hasStatusOk().bodyJson().extractingPath("$.username").isEqualTo("test-admin-1");
        assertThat(cookie(renewed, SessionCookies.ACCESS)).isNotBlank();
        assertThat(mvc.post().uri("/api/v1/auth/refresh").exchange()).hasStatus(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void refreshTokenIsNeverAcceptedAsAccessToken() {
        MvcTestResult login = postJson("/api/v1/auth/session", ADMIN_1);
        String refresh = cookie(login, SessionCookies.REFRESH);

        assertThat(companies("Authorization", "Bearer " + refresh)).hasStatus(HttpStatus.UNAUTHORIZED);
        assertThat(mvc.post().uri("/api/v1/customers/companies").cookie(new Cookie(SessionCookies.ACCESS, refresh))
                .contentType(MediaType.APPLICATION_JSON).content(CUSTOMER_REQUEST).exchange())
                .hasStatus(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void accessTokenCannotRefreshTheSession() {
        String access = bearerToken(ADMIN_1);

        assertThat(mvc.post().uri("/api/v1/auth/refresh").cookie(new Cookie(SessionCookies.REFRESH, access)).exchange())
                .hasStatus(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void logoutRevokesTheSessionAndClearsCookies() {
        MvcTestResult login = postJson("/api/v1/auth/session", ADMIN_1);
        Cookie access = new Cookie(SessionCookies.ACCESS, cookie(login, SessionCookies.ACCESS));
        Cookie refresh = new Cookie(SessionCookies.REFRESH, cookie(login, SessionCookies.REFRESH));

        MvcTestResult logout = mvc.post().uri("/api/v1/auth/logout").cookie(access, refresh).exchange();

        assertThat(logout).hasStatus(HttpStatus.NO_CONTENT);
        assertThat(logout.getResponse().getHeaders(HttpHeaders.SET_COOKIE)).hasSize(2)
                .allSatisfy(c -> assertThat(c).contains("Max-Age=0"));
        assertThat(mvc.get().uri("/api/v1/auth/session").cookie(access).exchange()).hasStatus(HttpStatus.UNAUTHORIZED);
        assertThat(mvc.post().uri("/api/v1/auth/refresh").cookie(refresh).exchange()).hasStatus(HttpStatus.UNAUTHORIZED);
        assertThat(mvc.post().uri("/api/v1/auth/logout").exchange()).hasStatus(HttpStatus.NO_CONTENT);
    }

    @Test
    void refreshIgnoresTheLocalCacheSoARevocationOnAnotherInstanceCountsImmediately() {
        MvcTestResult login = postJson("/api/v1/auth/session", ADMIN_1);
        Cookie access = new Cookie(SessionCookies.ACCESS, cookie(login, SessionCookies.ACCESS));
        Cookie refresh = new Cookie(SessionCookies.REFRESH, cookie(login, SessionCookies.REFRESH));
        assertThat(mvc.get().uri("/api/v1/auth/session").cookie(access).exchange()).hasStatusOk(); // cache: ativa

        String sessionId = tokens.decodeRefresh(refresh.getValue()).getClaimAsString(TokenService.SESSION_ID);
        store.revoke(sessionId); // logout recebido por outra instância: o cache desta não fica sabendo

        assertThat(mvc.post().uri("/api/v1/auth/refresh").cookie(refresh).exchange()).hasStatus(HttpStatus.UNAUTHORIZED);
        assertThat(mvc.get().uri("/api/v1/auth/session").cookie(access).exchange()).hasStatus(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void staleAccessCookieDoesNotBlockLoginOrLogout() {
        Cookie stale = new Cookie(SessionCookies.ACCESS, "expired.or.garbage");

        assertThat(mvc.post().uri("/api/v1/auth/session").cookie(stale)
                .contentType(MediaType.APPLICATION_JSON).content(ADMIN_1).exchange()).hasStatusOk();
        assertThat(mvc.post().uri("/api/v1/auth/logout").cookie(stale,
                new Cookie(SessionCookies.REFRESH, "garbage")).exchange()).hasStatus(HttpStatus.NO_CONTENT);
    }

    @Test
    @SuppressWarnings("unchecked")
    void jwksPublishesOnlyThePublicKey() {
        MvcTestResult result = mvc.get().uri("/api/v1/auth/jwks").exchange();

        assertThat(result).hasStatusOk().bodyJson().convertTo(Map.class).satisfies(jwks -> {
            List<Map<String, Object>> keys = (List<Map<String, Object>>) jwks.get("keys");
            assertThat(keys).singleElement().satisfies(key -> {
                assertThat(key).containsEntry("kty", "RSA").containsEntry("alg", "RS256").containsKey("kid");
                assertThat(key).doesNotContainKeys("d", "p", "q");
            });
        });
    }

    @Test
    void blankCredentialsAreA400() {
        assertThat(postJson("/api/v1/auth/session", "{\"username\":\" \",\"password\":\"x\"}"))
                .hasStatus(HttpStatus.BAD_REQUEST);
    }

    private String bearerToken(String credentials) {
        MvcTestResult result = postJson("/api/v1/auth/token", credentials);
        assertThat(result).hasStatusOk().bodyJson().extractingPath("$.tokenType").isEqualTo("Bearer");
        String body = new String(result.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
        return body.replaceAll(".*\"accessToken\":\"([^\"]+)\".*", "$1");
    }

    private MvcTestResult companies(String header, String value) {
        return mvc.post().uri("/api/v1/customers/companies").header(header, value)
                .contentType(MediaType.APPLICATION_JSON).content(CUSTOMER_REQUEST).exchange();
    }

    private MvcTestResult postJson(String uri, String body) {
        return mvc.post().uri(uri).contentType(MediaType.APPLICATION_JSON).content(body).exchange();
    }

    private static String cookie(MvcTestResult result, String name) {
        return Arrays.stream(result.getResponse().getCookies())
                .filter(c -> c.getName().equals(name))
                .map(Cookie::getValue)
                .findFirst()
                .orElseThrow(() -> new AssertionError("missing cookie " + name));
    }
}
