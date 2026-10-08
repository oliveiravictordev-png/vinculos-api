package com.teste.vinculos.web.dto;

import com.teste.vinculos.web.ApiDocs;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/** Request do endpoint 2: a chave do cliente e as empresas a consultar. */
public record RecordsRequest(
        @Schema(description = ApiDocs.YEAR, example = ApiDocs.YEAR_EXAMPLE)
        Integer year,
        @Schema(description = ApiDocs.DOCUMENT_TYPE, allowableValues = {"CPF", "CNPJ"}, example = ApiDocs.DOCUMENT_TYPE_EXAMPLE)
        String documentType,
        @Schema(description = ApiDocs.DOCUMENT, example = ApiDocs.DOCUMENT_EXAMPLE)
        String document,
        @Schema(description = ApiDocs.COMPANIES, example = ApiDocs.COMPANIES_EXAMPLE)
        List<String> companies) implements CustomerKeyRequest {
}
