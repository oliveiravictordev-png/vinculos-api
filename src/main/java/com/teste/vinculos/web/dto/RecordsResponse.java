package com.teste.vinculos.web.dto;

import com.teste.vinculos.domain.CompanyRecords;
import com.teste.vinculos.domain.CustomerRecord;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record RecordsResponse(List<Company> companies) {

    public record Company(String company, List<Item> records) {
    }

    public record Item(long id, String product, BigDecimal amount, Instant updatedAt) {
    }

    public static RecordsResponse from(List<CompanyRecords> data) {
        return new RecordsResponse(data.stream()
                .map(c -> new Company(c.company(), c.records().stream().map(RecordsResponse::item).toList()))
                .toList());
    }

    private static Item item(CustomerRecord r) {
        return new Item(r.id(), r.product(), r.amount(), r.updatedAt());
    }
}
