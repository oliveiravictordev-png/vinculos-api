package com.teste.vinculos.web;

import org.apache.logging.log4j.ThreadContext;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class RequestIdFilterTest {

    private final RequestIdFilter filter = new RequestIdFilter();

    @Test
    void reusesASafeIncomingIdInResponseLogsAndRequest() throws Exception {
        var request = new MockHttpServletRequest("POST", "/api/v1/customers/companies");
        request.addHeader(RequestIdFilter.HEADER, "abc-123_XYZ.789");
        var response = new MockHttpServletResponse();
        var seenInLogs = new AtomicReference<String>();

        filter.doFilter(request, response, (req, res) -> seenInLogs.set(ThreadContext.get(RequestIdFilter.LOG_KEY)));

        assertThat(response.getHeader(RequestIdFilter.HEADER)).isEqualTo("abc-123_XYZ.789");
        assertThat(seenInLogs).hasValue("abc-123_XYZ.789");
        assertThat(RequestIdFilter.current(request)).isEqualTo("abc-123_XYZ.789");
        assertThat(ThreadContext.get(RequestIdFilter.LOG_KEY)).isNull();
    }

    @Test
    void replacesUnsafeOrMissingIdWithAUuid() throws Exception {
        var request = new MockHttpServletRequest("GET", "/");
        request.addHeader(RequestIdFilter.HEADER, "evil\nINFO forged log line");
        var response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) -> { });

        assertThat(response.getHeader(RequestIdFilter.HEADER))
                .matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    }
}
