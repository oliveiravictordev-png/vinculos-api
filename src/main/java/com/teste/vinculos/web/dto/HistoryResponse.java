package com.teste.vinculos.web.dto;

import com.teste.vinculos.domain.AuditEntry;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;

/** Últimas consultas do usuário logado, da mais recente para a mais antiga. Objeto (e não lista) para poder crescer. */
public record HistoryResponse(List<Item> items) {

    public record Item(
            @Schema(allowableValues = {"COMPANIES", "RECORDS", "SEARCH", "EXPORT_CSV", "EXPORT_XLSX"})
            String action,
            Instant at,
            int year,
            String documentType,
            @Schema(description = "Documento mascarado (LGPD)", example = "056******17")
            String document,
            @Schema(allowableValues = {"SUCCESS", "REJECTED", "FAILED"})
            String outcome,
            int resultCount,
            long durationMs) {
    }

    public static HistoryResponse from(List<AuditEntry.View> views) {
        return new HistoryResponse(views.stream()
                .map(v -> new Item(v.action().name(), v.at(), v.year(), v.documentType().name(), v.maskedDocument(),
                        v.outcome().name(), v.resultCount(), v.durationMs()))
                .toList());
    }
}
