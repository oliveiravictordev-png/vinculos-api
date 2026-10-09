package com.teste.vinculos.application;

import com.teste.vinculos.domain.CustomerKey;
import com.teste.vinculos.domain.CustomerRecord;
import com.teste.vinculos.domain.RecordCursor;
import com.teste.vinculos.domain.RecordFilter;
import com.teste.vinculos.domain.RecordPage;
import com.teste.vinculos.domain.RecordSearchGateway;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;

class SearchRecordsUseCaseTest {

    private static final CustomerKey KEY = CustomerKey.of(2026, "CPF", "05685862717");
    private static final RecordPage.Totals TOTALS = new RecordPage.Totals(7, 2, new BigDecimal("70.00"));

    private final FakeGateway gateway = new FakeGateway();
    private final SearchRecordsUseCase useCase = new SearchRecordsUseCase(gateway);

    @Test
    void walksAllPagesWithoutRepeatingOrSkipping() {
        gateway.records = records(7);
        var seen = new ArrayList<Long>();
        String cursor = null;
        int pages = 0;
        do {
            RecordPage page = useCase.execute(RecordFilter.of(KEY, null, null, null, null, 3, cursor));
            page.records().forEach(r -> seen.add(r.id()));
            assertThat(page.totals()).isEqualTo(TOTALS);
            cursor = page.nextCursor();
            pages++;
        } while (cursor != null);

        assertThat(pages).isEqualTo(3);
        assertThat(seen).containsExactlyElementsOf(gateway.records.stream().map(CustomerRecord::id).toList());
    }

    @Test
    void lastPageHasNoCursor() {
        gateway.records = records(3);

        RecordPage page = useCase.execute(RecordFilter.of(KEY, null, null, null, null, 3, null));

        assertThat(page.records()).hasSize(3);
        assertThat(page.nextCursor()).isNull();
    }

    @Test
    void exportStartsFromTheBeginningAndFlagsTruncation() {
        gateway.records = records(SearchRecordsUseCase.MAX_EXPORT_ROWS + 1);
        String middle = RecordCursor.after(gateway.records.get(10)).encode();

        RecordPage export = useCase.export(RecordFilter.of(KEY, null, null, null, null, 5, middle));

        assertThat(export.records()).hasSize(SearchRecordsUseCase.MAX_EXPORT_ROWS);
        assertThat(export.records().getFirst()).isEqualTo(gateway.records.getFirst());
        assertThat(export.nextCursor()).isNotNull();
    }

    private static List<CustomerRecord> records(int count) {
        return LongStream.range(0, count)
                .mapToObj(i -> new CustomerRecord(i, i % 2 == 0 ? "10007037000103" : "10014956000104", "SEGURO",
                        BigDecimal.TEN, Instant.EPOCH))
                .sorted(Comparator.comparing(CustomerRecord::company).thenComparingLong(CustomerRecord::id))
                .toList();
    }

    /** Aplica cursor e limite como o adapter Mongo, sobre uma lista já ordenada por (empresa, id). */
    private static class FakeGateway implements RecordSearchGateway {

        List<CustomerRecord> records = List.of();

        @Override
        public List<CustomerRecord> search(RecordFilter filter, int limit) {
            RecordCursor cursor = filter.cursor();
            return records.stream()
                    .filter(r -> cursor == null || r.company().compareTo(cursor.company()) > 0
                            || (r.company().equals(cursor.company()) && r.id() > cursor.id()))
                    .limit(limit)
                    .toList();
        }

        @Override
        public RecordPage.Totals totals(RecordFilter filter) {
            return TOTALS;
        }
    }
}
