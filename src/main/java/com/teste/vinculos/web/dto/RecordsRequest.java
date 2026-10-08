package com.teste.vinculos.web.dto;

import com.teste.vinculos.domain.CustomerKey;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

public record RecordsRequest(
        @Schema(description = "Ano (1900 a 2100)", example = "2026")
        Integer year,
        @Schema(description = "Tipo do documento", allowableValues = {"CPF", "CNPJ"}, example = "CPF")
        String documentType,
        @Schema(description = "CPF ou CNPJ, com ou sem pontuação (CNPJ alfanumérico aceito)", example = "056.858.627-17")
        String document,
        @Schema(description = "CNPJs das empresas (1 a 100); duplicatas e pontuação são normalizadas",
                example = "[\"10007037000103\", \"10014956000104\", \"10022875000148\", \"10049118000168\"]")
        List<String> companies) {

    public CustomerKey key() {
        return CustomerKey.of(year, documentType, document);
    }
}
