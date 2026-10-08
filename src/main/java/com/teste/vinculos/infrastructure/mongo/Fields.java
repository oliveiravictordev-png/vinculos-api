package com.teste.vinculos.infrastructure.mongo;

/**
 * Nomes curtos de campo: com 1 bilhão de documentos, cada byte do nome do campo
 * se repete em todos eles (~1 GB por byte economizado, antes da compressão).
 */
public final class Fields {

    public static final String COLLECTION = "vinculos";

    public static final String ID = "_id";
    public static final String YEAR = "a";
    public static final String TYPE = "t";
    public static final String DOCUMENT = "v";
    public static final String COMPANY = "e";
    public static final String PRODUCT = "p";
    public static final String AMOUNT_CENTS = "s";
    public static final String UPDATED_AT = "u";

    private Fields() {
    }
}
