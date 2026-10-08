package com.teste.vinculos.web.dto;

import com.teste.vinculos.domain.CustomerKey;
import io.swagger.v3.oas.annotations.media.Schema;

public record CompaniesRequest(
        @Schema(description = "Ano (1900 a 2100)", example = "2026")
        Integer year,
        @Schema(description = "Tipo do documento", allowableValues = {"CPF", "CNPJ"}, example = "CPF")
        String documentType,
        @Schema(description = "CPF ou CNPJ, com ou sem pontuação (CNPJ alfanumérico aceito)", example = "056.858.627-17")
        String document) {

    public CustomerKey key() {
        return CustomerKey.of(year, documentType, document);
    }
}
