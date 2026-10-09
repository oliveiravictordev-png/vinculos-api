package com.teste.vinculos.domain;

import java.util.Collection;
import java.util.List;
import java.util.TreeSet;

/**
 * Lista de CNPJs de empresas recebida numa consulta. Dono único do limite por consulta: o endpoint 2, a busca
 * paginada e a exportação aplicam a mesma regra.
 */
public final class Companies {

    /** Mais empresas por consulta viraria um {@code $in} grande demais para o índice e para o cache. */
    public static final int MAX = 100;

    private Companies() {
    }

    /**
     * Valida, normaliza, ordena e remove duplicatas. Lista nula ou vazia vira lista vazia: cada caso de uso decide
     * se "nenhuma empresa" é erro (endpoint 2) ou "todas" (busca). Ordenar deixa a consulta e a chave de cache
     * determinísticas.
     */
    public static List<String> normalize(Collection<String> companies) {
        if (companies == null || companies.isEmpty()) {
            return List.of();
        }
        if (companies.size() > MAX) {
            throw new InvalidDataException("companies accepts at most " + MAX + " CNPJs");
        }
        var unique = new TreeSet<String>();
        for (String company : companies) {
            String cnpj = Documents.normalize(company);
            if (!Documents.isValidCnpj(cnpj)) {
                throw new InvalidDataException("invalid company CNPJ: " + cnpj);
            }
            unique.add(cnpj);
        }
        return List.copyOf(unique);
    }
}
