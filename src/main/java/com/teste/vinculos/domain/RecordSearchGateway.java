package com.teste.vinculos.domain;

import java.util.List;

/**
 * Porta de saída para a busca paginada. Separada de {@link CustomerGateway} (segregação de interfaces): os
 * endpoints 1 e 2 não precisam saber de filtros, cursor nem totais.
 */
public interface RecordSearchGateway {

    /** Até {@code limit} registros que atendem ao filtro, depois do cursor, ordenados por (empresa, id). */
    List<CustomerRecord> search(RecordFilter filter, int limit);

    /** Totais de todos os registros que atendem ao filtro, ignorando cursor e limite. */
    RecordPage.Totals totals(RecordFilter filter);
}
