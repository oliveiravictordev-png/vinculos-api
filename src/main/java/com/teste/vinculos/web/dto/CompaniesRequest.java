package com.teste.vinculos.web.dto;

import com.teste.vinculos.web.ApiDocs;
import io.swagger.v3.oas.annotations.media.Schema;

/** Request do endpoint 1: a chave do cliente. */
public record CompaniesRequest(
        @Schema(description = ApiDocs.YEAR, example = ApiDocs.YEAR_EXAMPLE)
        Integer year,
        @Schema(description = ApiDocs.DOCUMENT_TYPE, allowableValues = {"CPF", "CNPJ"}, example = ApiDocs.DOCUMENT_TYPE_EXAMPLE)
        String documentType,
        @Schema(description = ApiDocs.DOCUMENT, example = ApiDocs.DOCUMENT_EXAMPLE)
        String document) implements CustomerKeyRequest {
}
