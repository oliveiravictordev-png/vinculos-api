package com.teste.vinculos.application;

import com.teste.vinculos.domain.CustomerRecord;
import com.teste.vinculos.domain.RecordCursor;
import com.teste.vinculos.domain.RecordFilter;
import com.teste.vinculos.domain.RecordPage;
import com.teste.vinculos.domain.RecordSearchGateway;

import java.util.List;

/**
 * Busca paginada com filtros e totais, usada pela tela e pela exportação. O endpoint 2 continua existindo como o
 * enunciado pede; esta busca é para quando uma empresa tem muitos registros ou o usuário quer filtrar.
 */
public class SearchRecordsUseCase {

    /** Teto da exportação: um arquivo maior que isso pede um processo assíncrono, não uma requisição HTTP. */
    public static final int MAX_EXPORT_ROWS = 5_000;

    private final RecordSearchGateway gateway;

    public SearchRecordsUseCase(RecordSearchGateway gateway) {
        this.gateway = gateway;
    }

    /** Uma página do tamanho pedido no filtro. */
    public RecordPage execute(RecordFilter filter) {
        return page(filter, filter.limit());
    }

    /**
     * Até {@link #MAX_EXPORT_ROWS} registros a partir do início, ignorando cursor e tamanho de página. Um
     * {@code nextCursor} não nulo no resultado indica que o arquivo foi cortado no teto.
     */
    public RecordPage export(RecordFilter filter) {
        var fromStart = new RecordFilter(filter.key(), filter.companies(), filter.product(), filter.updatedFrom(),
                filter.updatedTo(), RecordFilter.MAX_LIMIT, null);
        return page(fromStart, MAX_EXPORT_ROWS);
    }

    // Pede um registro a mais que o limite: se ele vier, existe próxima página, sem precisar de um count.
    private RecordPage page(RecordFilter filter, int limit) {
        List<CustomerRecord> found = gateway.search(filter, limit + 1);
        boolean hasMore = found.size() > limit;
        List<CustomerRecord> records = hasMore ? found.subList(0, limit) : found;
        String next = hasMore ? RecordCursor.after(records.getLast()).encode() : null;
        return new RecordPage(records, next, gateway.totals(filter));
    }
}
