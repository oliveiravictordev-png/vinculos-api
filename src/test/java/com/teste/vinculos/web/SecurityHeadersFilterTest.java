package com.teste.vinculos.web;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

class SecurityHeadersFilterTest {

    private final SecurityHeadersFilter filter = new SecurityHeadersFilter();

    @Test
    void apiResponsesAreNotCachedAndHaveTheStrictestPolicy() throws Exception {
        MockHttpServletResponse response = call("/api/v1/customers/companies", false);

        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(response.getHeader("Content-Security-Policy")).isEqualTo(SecurityHeadersFilter.API_CSP);
        assertThat(response.getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(response.getHeader("X-Frame-Options")).isEqualTo("DENY");
        assertThat(response.getHeader("Referrer-Policy")).isEqualTo("no-referrer");
        assertThat(response.getHeader("Strict-Transport-Security")).isNull();
    }

    @Test
    void swaggerKeepsWorkingWithASameOriginPolicy() throws Exception {
        MockHttpServletResponse response = call("/swagger-ui/index.html", false);

        assertThat(response.getHeader("Content-Security-Policy")).isEqualTo(SecurityHeadersFilter.DOCS_CSP);
        assertThat(response.getHeader("Cache-Control")).isNull();
    }

    @Test
    void hstsOnlyOverHttps() throws Exception {
        assertThat(call("/actuator/health", true).getHeader("Strict-Transport-Security")).startsWith("max-age=31536000");
    }

    private MockHttpServletResponse call(String uri, boolean secure) throws Exception {
        var request = new MockHttpServletRequest("GET", uri);
        request.setRequestURI(uri);
        request.setSecure(secure);
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        return response;
    }
}
