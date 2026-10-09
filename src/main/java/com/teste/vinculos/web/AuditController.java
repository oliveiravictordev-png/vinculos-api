package com.teste.vinculos.web;

import com.teste.vinculos.application.AuditQueriesUseCase;
import com.teste.vinculos.web.dto.HistoryResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;

/**
 * Histórico de consultas do próprio usuário (cada um só vê as suas). GET sem dado pessoal na URL: o histórico já
 * vem com o documento mascarado.
 */
@RestController
@Profile("!seed")
@RequestMapping("/api/v1/audit")
@Tag(name = "Audit", description = "Trilha de auditoria das consultas")
@SecurityRequirement(name = "bearerAuth")
@ApiResponse(responseCode = "401", description = "Token ausente, expirado, inválido ou de sessão revogada",
        content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
@ApiResponse(responseCode = "403", description = "Token sem o escopo audit:read",
        content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
public class AuditController {

    private final AuditQueriesUseCase audit;

    public AuditController(AuditQueriesUseCase audit) {
        this.audit = audit;
    }

    @GetMapping("/history")
    @Operation(summary = "Últimas consultas do usuário logado",
            description = "Da mais recente para a mais antiga, com resultado, quantidade e duração de cada uma.")
    @ApiResponse(responseCode = "200", description = "Histórico")
    @ApiResponse(responseCode = "400", description = "limit fora de 1 a " + AuditQueriesUseCase.MAX_HISTORY,
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    public HistoryResponse history(
            @Parameter(description = "Quantidade (1 a " + AuditQueriesUseCase.MAX_HISTORY + ")")
            @RequestParam(defaultValue = "20") int limit,
            Principal principal) {
        return HistoryResponse.from(audit.history(principal == null ? Callers.ANONYMOUS : principal.getName(), limit));
    }
}
