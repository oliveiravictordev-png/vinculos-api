package com.teste.vinculos.domain;

import java.math.BigDecimal;
import java.util.List;

/**
 * Uma página da busca: os registros, o cursor da próxima página ({@code null} na última) e os totais de todos os
 * registros que atendem ao filtro, não só desta página.
 */
public record RecordPage(List<CustomerRecord> records, String nextCursor, Totals totals) {

    public RecordPage {
        records = List.copyOf(records);
    }

    /** Totais do filtro inteiro: quantidade de registros, de empresas distintas e soma dos valores. */
    public record Totals(long records, int companies, BigDecimal amount) {

        public static final Totals EMPTY = new Totals(0, 0, BigDecimal.ZERO.setScale(2));
    }
}
