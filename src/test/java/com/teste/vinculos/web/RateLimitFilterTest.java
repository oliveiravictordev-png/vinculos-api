package com.teste.vinculos.web;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class RateLimitFilterTest {

    private final AtomicLong clock = new AtomicLong();
    private final RateLimitFilter filter = new RateLimitFilter(
            new RateLimitProperties(true, 3, 1, 100, 100), clock::get);

    @Test
    void allowsBurstThenRejectsWithRetryAfter() throws Exception {
        for (int i = 0; i < 3; i++) {
            assertThat(call("10.0.0.1", "/api/v1/customers/companies").getStatus()).isEqualTo(200);
        }

        MockHttpServletResponse rejected = call("10.0.0.1", "/api/v1/customers/companies");

        assertThat(rejected.getStatus()).isEqualTo(429);
        assertThat(rejected.getHeader("Retry-After")).isEqualTo("1");
        assertThat(rejected.getContentType()).startsWith("application/problem+json");
        assertThat(rejected.getContentAsString()).contains("\"status\":429");
    }

    @Test
    void refillsOverTime() throws Exception {
        for (int i = 0; i < 3; i++) {
            call("10.0.0.1", "/api/v1/customers/companies");
        }
        assertThat(call("10.0.0.1", "/api/v1/customers/companies").getStatus()).isEqualTo(429);

        clock.addAndGet(1_000_000_000L);

        assertThat(call("10.0.0.1", "/api/v1/customers/companies").getStatus()).isEqualTo(200);
    }

    @Test
    void limitsEachIpSeparately() throws Exception {
        for (int i = 0; i < 3; i++) {
            call("10.0.0.1", "/api/v1/customers/companies");
        }

        assertThat(call("10.0.0.2", "/api/v1/customers/companies").getStatus()).isEqualTo(200);
    }

    @Test
    void appliesGlobalLimitAcrossIps() throws Exception {
        var tight = new RateLimitFilter(new RateLimitProperties(true, 10, 10, 2, 1), clock::get);

        assertThat(call(tight, "10.0.0.1", "/api/v1/customers/companies").getStatus()).isEqualTo(200);
        assertThat(call(tight, "10.0.0.2", "/api/v1/customers/companies").getStatus()).isEqualTo(200);
        assertThat(call(tight, "10.0.0.3", "/api/v1/customers/companies").getStatus()).isEqualTo(429);
    }

    @Test
    void ignoresNonApiPaths() throws Exception {
        for (int i = 0; i < 10; i++) {
            assertThat(call("10.0.0.1", "/actuator/health").getStatus()).isEqualTo(200);
        }
    }

    private MockHttpServletResponse call(String ip, String path) throws Exception {
        return call(filter, ip, path);
    }

    private static MockHttpServletResponse call(RateLimitFilter filter, String ip, String path) throws Exception {
        var request = new MockHttpServletRequest("POST", path);
        request.setRequestURI(path);
        request.setRemoteAddr(ip);
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        return response;
    }
}
