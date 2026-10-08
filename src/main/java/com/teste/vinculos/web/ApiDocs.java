package com.teste.vinculos.web;

import com.teste.vinculos.domain.CustomerKey;

/**
 * Textos da documentação OpenAPI usados em mais de um lugar (DTOs e controller). Os limites vêm do domínio,
 * então a documentação não diverge da validação.
 */
public final class ApiDocs {

    public static final String YEAR = "Ano (" + CustomerKey.MIN_YEAR + " a " + CustomerKey.MAX_YEAR + ")";
    public static final String DOCUMENT_TYPE = "Tipo do documento";
    public static final String DOCUMENT = "CPF ou CNPJ, com ou sem pontuação (CNPJ alfanumérico aceito)";
    public static final String COMPANIES = "CNPJs das empresas (1 a 100); duplicatas e pontuação são normalizadas";

    // Chave do enunciado (cliente 140.575.883 da carga): existe na base local de 1 bilhão e na demonstração pública.
    public static final String YEAR_EXAMPLE = "2026";
    public static final String DOCUMENT_TYPE_EXAMPLE = "CPF";
    public static final String DOCUMENT_EXAMPLE = "056.858.627-17";
    public static final String COMPANIES_EXAMPLE =
            "[\"10007037000103\", \"10014956000104\", \"10022875000148\", \"10049118000168\"]";

    public static final String INVALID_DATA = "Dado inválido (ano fora de " + CustomerKey.MIN_YEAR + "-" + CustomerKey.MAX_YEAR
            + ", tipo desconhecido, dígito verificador errado)";

    private ApiDocs() {
    }
}
