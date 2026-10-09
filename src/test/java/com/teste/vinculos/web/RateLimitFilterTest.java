package com.teste.vinculos.web;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.web.servlet.ModelAndView;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class RateLimitFilterTest {

    private final AtomicLong clock = new AtomicLong();
    private final List<Exception> resolved = new ArrayList<>();
    private final HandlerExceptionResolver resolver = (request, response, handler, ex) -> {
        resolved.add(ex);
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        return new ModelAndView();
    };
    private final RateLimitFilter filter = new RateLimitFilter(
            new RateLimitProperties(true, 3, 1, 100, 100, 2, 6), resolver, clock::get);

    @Test
    void allowsBurstThenDelegatesRejectionToTheExceptionHandler() throws Exception {
        for (int i = 0; i < 3; i++) {
            assertThat(call("10.0.0.1", "/api/v1/customers/companies").getStatus()).isEqualTo(200);
        }

        assertThat(call("10.0.0.1", "/api/v1/customers/companies").getStatus()).isEqualTo(429);
        assertThat(resolved).singleElement()
                .isInstanceOfSatisfying(RateLimitExceededException.class, e -> assertThat(e.retryAfterSeconds()).isEqualTo(1));
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
        var tight = new RateLimitFilter(new RateLimitProperties(true, 10, 10, 2, 1, 2, 6), resolver, clock::get);

        assertThat(call(tight, "10.0.0.1", "/api/v1/customers/companies").getStatus()).isEqualTo(200);
        assertThat(call(tight, "10.0.0.2", "/api/v1/customers/companies").getStatus()).isEqualTo(200);
        assertThat(call(tight, "10.0.0.3", "/api/v1/customers/companies").getStatus()).isEqualTo(429);
    }

    @Test
    void exportHasItsOwnTighterLimitPerIp() throws Exception {
        assertThat(call("10.0.0.1", RateLimitFilter.EXPORT_PATH).getStatus()).isEqualTo(200);
        assertThat(call("10.0.0.1", RateLimitFilter.EXPORT_PATH).getStatus()).isEqualTo(200);
        assertThat(call("10.0.0.1", RateLimitFilter.EXPORT_PATH).getStatus()).isEqualTo(429);
        assertThat(resolved).singleElement()
                .isInstanceOfSatisfying(RateLimitExceededException.class, e -> assertThat(e.retryAfterSeconds()).isEqualTo(10));

        assertThat(call("10.0.0.1", "/api/v1/customers/companies").getStatus()).isEqualTo(200);
        assertThat(call("10.0.0.2", RateLimitFilter.EXPORT_PATH).getStatus()).isEqualTo(200);
    }

    @Test
    void ignoresNonApiPaths() throws Exception {
        for (int i = 0; i < 10; i++) {
            assertThat(call("10.0.0.1", "/actuator/health").getStatus()).isEqualTo(200);
        }
    }

    @Test
    void handlerAnswers429WithRetryAfterAndProblemDetail() {
        ResponseEntity<ProblemDetail> response = new ApiExceptionHandler().rateLimitExceeded(new RateLimitExceededException(3));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(response.getHeaders().getFirst("Retry-After")).isEqualTo("3");
        assertThat(response.getBody().getTitle()).isEqualTo("Too many requests");
        assertThat(response.getBody().getDetail()).isEqualTo("Request rate limit exceeded, retry in 3 s");
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
