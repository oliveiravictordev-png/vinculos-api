package com.teste.vinculos.application;

import com.teste.vinculos.domain.AuditEntry;
import com.teste.vinculos.domain.CustomerKey;
import com.teste.vinculos.domain.InvalidDataException;
import com.teste.vinculos.domain.QueryAuditLog;

import java.time.Clock;
import java.util.List;
import java.util.function.Supplier;
import java.util.function.ToIntFunction;

/**
 * Auditoria das consultas a dados de cliente. Envolve a consulta em vez de ser chamada depois dela, para registrar
 * também as que falham (dado inválido, banco fora), que são justamente as que mais interessam numa investigação.
 */
public class AuditQueriesUseCase {

    public static final int MAX_HISTORY = 100;

    private final QueryAuditLog log;
    private final Clock clock;

    public AuditQueriesUseCase(QueryAuditLog log, Clock clock) {
        this.log = log;
        this.clock = clock;
    }

    /** Quem está consultando e de qual requisição (para correlacionar com logs e traces). */
    public record Caller(String username, String requestId) {
    }

    /**
     * Executa a consulta e grava a auditoria com o resultado, inclusive quando ela lança exceção (que é repassada
     * sem alteração).
     */
    public <T> T run(Caller caller, AuditEntry.Action action, CustomerKey key, Supplier<T> query,
                     ToIntFunction<T> resultCount) {
        long start = System.nanoTime();
        AuditEntry.Outcome outcome = AuditEntry.Outcome.FAILED;
        int count = 0;
        try {
            T result = query.get();
            outcome = AuditEntry.Outcome.SUCCESS;
            count = resultCount.applyAsInt(result);
            return result;
        } catch (InvalidDataException e) {
            outcome = AuditEntry.Outcome.REJECTED;
            throw e;
        } finally {
            long durationMs = (System.nanoTime() - start) / 1_000_000;
            log.record(new AuditEntry(caller.username(), action, key, outcome, count, durationMs, clock.instant(),
                    caller.requestId()));
        }
    }

    /** Últimas consultas do próprio usuário (1 a {@link #MAX_HISTORY}). */
    public List<AuditEntry.View> history(String username, int limit) {
        if (limit < 1 || limit > MAX_HISTORY) {
            throw new InvalidDataException("limit must be between 1 and " + MAX_HISTORY);
        }
        return log.recent(username, limit);
    }
}
