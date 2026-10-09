package com.teste.vinculos.support;

import com.teste.vinculos.domain.AuditEntry;
import com.teste.vinculos.domain.Documents;
import com.teste.vinculos.domain.QueryAuditLog;

import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** Trilha de auditoria em memória (fake, em vez de mock), síncrona para os testes conferirem logo depois. */
public class InMemoryQueryAuditLog implements QueryAuditLog {

    public final List<AuditEntry> entries = new CopyOnWriteArrayList<>();

    @Override
    public void record(AuditEntry entry) {
        entries.add(entry);
    }

    @Override
    public List<AuditEntry.View> recent(String username, int limit) {
        return entries.stream()
                .filter(e -> e.username().equals(username))
                .sorted(Comparator.comparing(AuditEntry::at).reversed())
                .limit(limit)
                .map(e -> new AuditEntry.View(e.action(), e.at(), e.key().year(), e.key().type(),
                        Documents.mask(e.key().document()), e.outcome(), e.resultCount(), e.durationMs()))
                .toList();
    }
}
