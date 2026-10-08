package com.teste.vinculos.domain;

/**
 * Chave de busca do cliente: ano + tipo do documento + valor do documento.
 * Só existe em estado válido: o documento é normalizado e tem os dígitos verificadores conferidos.
 */
public record CustomerKey(int year, DocumentType type, String document) {

    public static final int MIN_YEAR = 1900;
    public static final int MAX_YEAR = 2100;

    public CustomerKey {
        if (year < MIN_YEAR || year > MAX_YEAR) {
            throw new InvalidDataException("year must be between " + MIN_YEAR + " and " + MAX_YEAR);
        }
        if (type == null) {
            throw new InvalidDataException("documentType is required");
        }
        document = Documents.normalize(document);
        if (!Documents.isValid(type, document)) {
            throw new InvalidDataException("invalid document for type " + type);
        }
    }

    public static CustomerKey of(Integer year, String type, String document) {
        if (year == null) {
            throw new InvalidDataException("year is required");
        }
        return new CustomerKey(year, DocumentType.of(type), document);
    }

    /** Documento mascarado: evita vazar dado pessoal em logs. */
    @Override
    public String toString() {
        return "CustomerKey[year=" + year + ", type=" + type + ", document=" + Documents.mask(document) + "]";
    }
}
