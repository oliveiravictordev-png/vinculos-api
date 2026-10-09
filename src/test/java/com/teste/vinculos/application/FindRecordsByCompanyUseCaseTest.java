package com.teste.vinculos.application;

import com.teste.vinculos.domain.Companies;
import com.teste.vinculos.domain.CompanyRecords;
import com.teste.vinculos.domain.CustomerGateway;
import com.teste.vinculos.domain.CustomerKey;
import com.teste.vinculos.domain.CustomerRecord;
import com.teste.vinculos.domain.Documents;
import com.teste.vinculos.domain.InvalidDataException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FindRecordsByCompanyUseCaseTest {

    private static final CustomerKey KEY = CustomerKey.of(2026, "CPF", "01000000109");
    private static final String COMPANY_A = "11222333000181";
    private static final String COMPANY_B = "12ABC34501DE35";

    private final FakeGateway gateway = new FakeGateway();
    private final FindRecordsByCompanyUseCase useCase = new FindRecordsByCompanyUseCase(gateway);

    @Test
    void groupsByCompanyAndIncludesCompanyWithoutRecords() {
        gateway.records = List.of(record(1, COMPANY_A), record(2, COMPANY_A));

        List<CompanyRecords> result = useCase.execute(KEY, List.of(COMPANY_B, COMPANY_A));

        assertThat(result).extracting(CompanyRecords::company).containsExactly(COMPANY_A, COMPANY_B);
        assertThat(result.get(0).records()).extracting(CustomerRecord::id).containsExactly(1L, 2L);
        assertThat(result.get(1).records()).isEmpty();
    }

    @Test
    void queriesWithNormalizedSortedDistinctCompanies() {
        useCase.execute(KEY, List.of("12.abc.345/01de-35", "11.222.333/0001-81", COMPANY_A));

        assertThat(gateway.queriedCompanies).containsExactly(COMPANY_A, COMPANY_B);
    }

    @Test
    void rejectsInvalidCompanyList() {
        List<String> overLimit = LongStream.rangeClosed(0, Companies.MAX)
                .mapToObj(i -> Documents.generateCnpj((10_000_000L + i) * 10_000 + 1))
                .toList();

        assertThatThrownBy(() -> useCase.execute(KEY, List.of())).isInstanceOf(InvalidDataException.class);
        assertThatThrownBy(() -> useCase.execute(KEY, null)).isInstanceOf(InvalidDataException.class);
        assertThatThrownBy(() -> useCase.execute(KEY, List.of("11222333000182"))).isInstanceOf(InvalidDataException.class);
        assertThatThrownBy(() -> useCase.execute(KEY, overLimit)).isInstanceOf(InvalidDataException.class);
        assertThat(gateway.queriedCompanies).isNull();
    }

    private static CustomerRecord record(long id, String company) {
        return new CustomerRecord(id, company, "CARTAO_CREDITO", new BigDecimal("10.50"), Instant.parse("2026-01-01T00:00:00Z"));
    }

    private static class FakeGateway implements CustomerGateway {

        List<CustomerRecord> records = List.of();
        List<String> queriedCompanies;

        @Override
        public List<String> findCompanies(CustomerKey key) {
            return List.of();
        }

        @Override
        public List<CustomerRecord> findRecords(CustomerKey key, List<String> companies) {
            queriedCompanies = companies;
            return records;
        }
    }
}
