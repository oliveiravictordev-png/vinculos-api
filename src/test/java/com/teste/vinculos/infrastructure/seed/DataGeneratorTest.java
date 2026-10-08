package com.teste.vinculos.infrastructure.seed;

import com.teste.vinculos.domain.CustomerKey;
import com.teste.vinculos.domain.Documents;
import com.teste.vinculos.infrastructure.mongo.Fields;
import org.bson.Document;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;

class DataGeneratorTest {

    @Test
    void isDeterministic() {
        assertThat(generate(42)).isEqualTo(generate(42));
    }

    @Test
    void generatesFiveRecordsWithContiguousIdsAndCustomerCompanies() {
        List<Document> docs = generate(7);

        assertThat(docs).extracting(d -> d.getLong(Fields.ID)).containsExactly(35L, 36L, 37L, 38L, 39L);
        Set<String> companiesInRecords = docs.stream()
                .map(d -> d.getString(Fields.COMPANY))
                .collect(Collectors.toSet());
        assertThat(companiesInRecords)
                .isEqualTo(Set.copyOf(DataGenerator.companies(7)))
                .allMatch(Documents::isValidCnpj);
    }

    @Test
    void customerCompaniesAreDistinct() {
        LongStream.range(0, 10_000).forEach(customer -> {
            List<String> companies = DataGenerator.companies(customer);
            assertThat(companies).hasSizeBetween(1, 4).doesNotHaveDuplicates();
        });
    }

    @Test
    void keysAreUniqueAndValid() {
        Set<CustomerKey> keys = LongStream.range(0, 30_000)
                .mapToObj(DataGenerator::key)
                .collect(Collectors.toCollection(HashSet::new));

        assertThat(keys).hasSize(30_000);
    }

    private static List<Document> generate(long customer) {
        var docs = new ArrayList<Document>();
        DataGenerator.generate(customer, docs::add);
        return docs;
    }
}
