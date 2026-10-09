package com.teste.vinculos.domain;

import java.time.Instant;
import java.util.List;

/**
 * Critérios da busca paginada: a chave do cliente (que garante o uso do índice) mais filtros opcionais aplicados
 * sobre os poucos registros dessa chave.
 *
 * @param companies   empresas a considerar (vazia = todas as do cliente), já normalizadas
 * @param product     trecho do nome do produto, sem diferenciar maiúsculas; {@code null} = todos
 * @param updatedFrom início do período de atualização (inclusivo); {@code null} = sem limite
 * @param updatedTo   fim do período de atualização (inclusivo); {@code null} = sem limite
 * @param limit       tamanho da página
 * @param cursor      posição depois da qual a página começa; {@code null} = primeira página
 */
public record RecordFilter(CustomerKey key, List<String> companies, String product, Instant updatedFrom,
                           Instant updatedTo, int limit, RecordCursor cursor) {

    public static final int DEFAULT_LIMIT = 50;
    public static final int MAX_LIMIT = 200;
    public static final int MAX_PRODUCT_LENGTH = 40;

    public RecordFilter {
        if (key == null) {
            throw new InvalidDataException("customer key is required");
        }
        companies = Companies.normalize(companies);
        product = product == null || product.isBlank() ? null : product.trim();
        if (product != null && product.length() > MAX_PRODUCT_LENGTH) {
            throw new InvalidDataException("product must have at most " + MAX_PRODUCT_LENGTH + " characters");
        }
        if (updatedFrom != null && updatedTo != null && updatedFrom.isAfter(updatedTo)) {
            throw new InvalidDataException("updatedFrom must not be after updatedTo");
        }
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new InvalidDataException("limit must be between 1 and " + MAX_LIMIT);
        }
    }

    /** Monta o filtro a partir dos campos crus do request (limite e cursor opcionais). */
    public static RecordFilter of(CustomerKey key, List<String> companies, String product, Instant updatedFrom,
                                  Instant updatedTo, Integer limit, String cursor) {
        return new RecordFilter(key, companies, product, updatedFrom, updatedTo,
                limit == null ? DEFAULT_LIMIT : limit, RecordCursor.decode(cursor));
    }
}
