package com.teste.vinculos.web;

import com.teste.vinculos.application.AuditQueriesUseCase;
import jakarta.servlet.http.HttpServletRequest;

import java.security.Principal;

/** Monta quem está consultando, para a auditoria, a partir da requisição autenticada. */
final class Callers {

    /** Usuário registrado quando a requisição não passou pela autenticação (só acontece em testes de fatia). */
    static final String ANONYMOUS = "anonymous";

    private Callers() {
    }

    static AuditQueriesUseCase.Caller of(Principal principal, HttpServletRequest request) {
        return new AuditQueriesUseCase.Caller(principal == null ? ANONYMOUS : principal.getName(),
                RequestIdFilter.current(request));
    }
}
