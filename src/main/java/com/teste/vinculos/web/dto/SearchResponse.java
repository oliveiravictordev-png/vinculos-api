package com.teste.vinculos.web.dto;

import com.teste.vinculos.domain.CustomerRecord;
import com.teste.vinculos.domain.RecordPage;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** Uma página da busca, ordenada por (empresa, id), com os totais do filtro inteiro. */
public record SearchResponse(
        List<Item> items,
        @Schema(description = "Cursor da próxima página; null na última", nullable = true)
        String nextCursor,
        Totals totals) {

    public record Item(long id, String company, String product, BigDecimal amount, Instant updatedAt) {
    }

    @Schema(description = "Totais de todos os registros que atendem ao filtro, não só desta página")
    public record Totals(long records, int companies, BigDecimal amount) {
    }

    public static SearchResponse from(RecordPage page) {
        var totals = page.totals();
        return new SearchResponse(page.records().stream().map(SearchResponse::item).toList(), page.nextCursor(),
                new Totals(totals.records(), totals.companies(), totals.amount()));
    }

    private static Item item(CustomerRecord r) {
        return new Item(r.id(), r.company(), r.product(), r.amount(), r.updatedAt());
    }
}
