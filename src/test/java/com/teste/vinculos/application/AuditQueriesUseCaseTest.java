package com.teste.vinculos.application;

import com.teste.vinculos.domain.AuditEntry;
import com.teste.vinculos.domain.AuditEntry.Action;
import com.teste.vinculos.domain.AuditEntry.Outcome;
import com.teste.vinculos.domain.CustomerKey;
import com.teste.vinculos.domain.InvalidDataException;
import com.teste.vinculos.support.InMemoryQueryAuditLog;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuditQueriesUseCaseTest {

    private static final CustomerKey KEY = CustomerKey.of(2026, "CPF", "05685862717");
    private static final Instant NOW = Instant.parse("2026-10-08T12:00:00Z");
    private static final AuditQueriesUseCase.Caller CALLER = new AuditQueriesUseCase.Caller("gft-admin", "req-12345678");

    private final InMemoryQueryAuditLog log = new InMemoryQueryAuditLog();
    private final AuditQueriesUseCase useCase = new AuditQueriesUseCase(log, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void recordsSuccessfulQueryWithResultCount() {
        List<String> result = useCase.run(CALLER, Action.COMPANIES, KEY, () -> List.of("a", "b"), List::size);

        assertThat(result).containsExactly("a", "b");
        assertThat(log.entries).singleElement().satisfies(e -> {
            assertThat(e.username()).isEqualTo("gft-admin");
            assertThat(e.action()).isEqualTo(Action.COMPANIES);
            assertThat(e.key()).isEqualTo(KEY);
            assertThat(e.outcome()).isEqualTo(Outcome.SUCCESS);
            assertThat(e.resultCount()).isEqualTo(2);
            assertThat(e.at()).isEqualTo(NOW);
            assertThat(e.requestId()).isEqualTo("req-12345678");
        });
    }

    @Test
    void recordsRejectedAndFailedQueriesAndRethrows() {
        assertThatThrownBy(() -> useCase.run(CALLER, Action.SEARCH, KEY,
                () -> { throw new InvalidDataException("limit must be between 1 and 200"); }, x -> 1))
                .isInstanceOf(InvalidDataException.class);
        assertThatThrownBy(() -> useCase.run(CALLER, Action.RECORDS, KEY,
                () -> { throw new IllegalStateException("database down"); }, x -> 1))
                .isInstanceOf(IllegalStateException.class);

        assertThat(log.entries).extracting(AuditEntry::outcome).containsExactly(Outcome.REJECTED, Outcome.FAILED);
        assertThat(log.entries).extracting(AuditEntry::resultCount).containsOnly(0);
    }

    @Test
    void historyIsLimitedAndOnlyShowsTheCallerOwnQueries() {
        useCase.run(CALLER, Action.COMPANIES, KEY, () -> 1, x -> x);
        useCase.run(new AuditQueriesUseCase.Caller("other", null), Action.COMPANIES, KEY, () -> 1, x -> x);

        assertThat(useCase.history("gft-admin", 10)).singleElement()
                .satisfies(v -> assertThat(v.maskedDocument()).isEqualTo("056******17"));
        assertThatThrownBy(() -> useCase.history("gft-admin", 0)).isInstanceOf(InvalidDataException.class);
        assertThatThrownBy(() -> useCase.history("gft-admin", AuditQueriesUseCase.MAX_HISTORY + 1))
                .isInstanceOf(InvalidDataException.class);
    }
}
