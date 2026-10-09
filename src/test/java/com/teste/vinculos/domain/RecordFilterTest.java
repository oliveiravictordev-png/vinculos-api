package com.teste.vinculos.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RecordFilterTest {

    private static final CustomerKey KEY = CustomerKey.of(2026, "CPF", "05685862717");

    @Test
    void appliesDefaultsAndNormalizesOptionalFields() {
        RecordFilter filter = RecordFilter.of(KEY, null, "  cartao ", null, null, null, " ");

        assertThat(filter.limit()).isEqualTo(RecordFilter.DEFAULT_LIMIT);
        assertThat(filter.companies()).isEmpty();
        assertThat(filter.product()).isEqualTo("cartao");
        assertThat(filter.cursor()).isNull();
        assertThat(RecordFilter.of(KEY, List.of(), "  ", null, null, 10, null).product()).isNull();
    }

    @Test
    void normalizesCompaniesSortedAndDistinct() {
        RecordFilter filter = RecordFilter.of(KEY, List.of("12.abc.345/01de-35", "11.222.333/0001-81", "11222333000181"),
                null, null, null, null, null);

        assertThat(filter.companies()).containsExactly("11222333000181", "12ABC34501DE35");
    }

    @Test
    void rejectsLimitOutsideTheRange() {
        assertThatThrownBy(() -> RecordFilter.of(KEY, null, null, null, null, 0, null))
                .isInstanceOf(InvalidDataException.class).hasMessage("limit must be between 1 and 200");
        assertThatThrownBy(() -> RecordFilter.of(KEY, null, null, null, null, RecordFilter.MAX_LIMIT + 1, null))
                .isInstanceOf(InvalidDataException.class);
    }

    @Test
    void rejectsInvertedPeriodLongProductAndMissingKey() {
        Instant from = Instant.parse("2026-02-01T00:00:00Z");
        Instant to = Instant.parse("2026-01-01T00:00:00Z");

        assertThatThrownBy(() -> RecordFilter.of(KEY, null, null, from, to, null, null))
                .isInstanceOf(InvalidDataException.class).hasMessage("updatedFrom must not be after updatedTo");
        assertThatThrownBy(() -> RecordFilter.of(KEY, null, "x".repeat(RecordFilter.MAX_PRODUCT_LENGTH + 1), null, null, null, null))
                .isInstanceOf(InvalidDataException.class);
        assertThatThrownBy(() -> RecordFilter.of(null, null, null, null, null, null, null))
                .isInstanceOf(InvalidDataException.class);
    }

    @Test
    void rejectsTooManyOrInvalidCompanies() {
        List<String> overLimit = LongStream.rangeClosed(0, Companies.MAX)
                .mapToObj(i -> Documents.generateCnpj((10_000_000L + i) * 10_000 + 1))
                .toList();

        assertThatThrownBy(() -> RecordFilter.of(KEY, overLimit, null, null, null, null, null))
                .isInstanceOf(InvalidDataException.class);
        assertThatThrownBy(() -> RecordFilter.of(KEY, List.of("11222333000182"), null, null, null, null, null))
                .isInstanceOf(InvalidDataException.class).hasMessage("invalid company CNPJ: 11222333000182");
    }
}
