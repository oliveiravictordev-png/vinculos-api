package com.teste.vinculos.domain;

public enum DocumentType {
    CPF,
    CNPJ;

    public static DocumentType of(String value) {
        if (value != null) {
            for (DocumentType type : values()) {
                if (type.name().equalsIgnoreCase(value.trim())) {
                    return type;
                }
            }
        }
        throw new InvalidDataException("documentType must be CPF or CNPJ");
    }
}
