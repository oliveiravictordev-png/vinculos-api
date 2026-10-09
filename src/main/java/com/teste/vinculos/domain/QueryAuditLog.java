package com.teste.vinculos.domain;

import java.util.List;

/** Porta de saída da trilha de auditoria das consultas. */
public interface QueryAuditLog {

    /**
     * Grava o registro. A implementação pode gravar de forma assíncrona: a auditoria não deve deixar a consulta
     * mais lenta nem derrubá-la se o armazenamento falhar.
     */
    void record(AuditEntry entry);

    /** Últimas consultas do usuário, da mais recente para a mais antiga. */
    List<AuditEntry.View> recent(String username, int limit);
}
