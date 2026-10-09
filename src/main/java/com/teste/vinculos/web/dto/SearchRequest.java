package com.teste.vinculos.web.dto;

import com.teste.vinculos.domain.RecordFilter;
import com.teste.vinculos.web.ApiDocs;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;

/** Request da busca paginada e da exportação: a chave do cliente mais filtros opcionais. */
public record SearchRequest(
        @Schema(description = ApiDocs.YEAR, example = ApiDocs.YEAR_EXAMPLE)
        Integer year,
        @Schema(description = ApiDocs.DOCUMENT_TYPE, allowableValues = {"CPF", "CNPJ"}, example = ApiDocs.DOCUMENT_TYPE_EXAMPLE)
        String documentType,
        @Schema(description = ApiDocs.DOCUMENT, example = ApiDocs.DOCUMENT_EXAMPLE)
        String document,
        @Schema(description = ApiDocs.SEARCH_COMPANIES, example = ApiDocs.COMPANIES_EXAMPLE)
        List<String> companies,
        @Schema(description = ApiDocs.PRODUCT, example = "cartao")
        String product,
        @Schema(description = ApiDocs.UPDATED_FROM, example = "2026-01-01T00:00:00Z")
        Instant updatedFrom,
        @Schema(description = ApiDocs.UPDATED_TO, example = "2026-12-31T23:59:59Z")
        Instant updatedTo,
        @Schema(description = ApiDocs.LIMIT, example = "50")
        Integer limit,
        @Schema(description = ApiDocs.CURSOR)
        String cursor) implements CustomerKeyRequest {

    /** Converte para o domínio, onde ficam todas as validações. */
    public RecordFilter filter() {
        return RecordFilter.of(key(), companies, product, updatedFrom, updatedTo, limit, cursor);
    }
}
