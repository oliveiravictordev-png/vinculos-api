package com.teste.vinculos.domain;

import java.time.Instant;

/**
 * Registro de auditoria de uma consulta a dados de cliente: quem consultou, o quê, quando, com que resultado e em
 * quanto tempo. O documento completo nunca é gravado: o adapter guarda só a versão mascarada e um hash com chave
 * (permite achar todas as consultas a um CPF, mas não recuperar o CPF a partir do registro).
 *
 * @param requestId ID de correlação da requisição (o mesmo do cabeçalho {@code X-Request-Id} e dos logs)
 */
public record AuditEntry(String username, Action action, CustomerKey key, Outcome outcome, int resultCount,
                         long durationMs, Instant at, String requestId) {

    /** Consulta auditada. */
    public enum Action { COMPANIES, RECORDS, SEARCH, EXPORT_CSV, EXPORT_XLSX }

    /** Resultado da consulta: concluída, recusada por dado inválido ou falha (banco, erro inesperado). */
    public enum Outcome { SUCCESS, REJECTED, FAILED }

    /** Item do histórico como o usuário o vê: sem hash e com o documento mascarado. */
    public record View(Action action, Instant at, int year, DocumentType documentType, String maskedDocument,
                       Outcome outcome, int resultCount, long durationMs) {
    }
}
