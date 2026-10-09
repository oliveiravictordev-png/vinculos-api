package com.teste.vinculos.web;

import com.teste.vinculos.application.AuditQueriesUseCase;
import com.teste.vinculos.application.FindCompaniesUseCase;
import com.teste.vinculos.application.FindRecordsByCompanyUseCase;
import com.teste.vinculos.application.SearchRecordsUseCase;
import com.teste.vinculos.domain.AuditEntry.Action;
import com.teste.vinculos.domain.CustomerKey;
import com.teste.vinculos.web.dto.CompaniesRequest;
import com.teste.vinculos.web.dto.CompaniesResponse;
import com.teste.vinculos.web.dto.RecordsRequest;
import com.teste.vinculos.web.dto.RecordsResponse;
import com.teste.vinculos.web.dto.SearchRequest;
import com.teste.vinculos.web.dto.SearchResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;

/**
 * POST com corpo JSON: CPF/CNPJ não vai para a URL (logs de acesso, proxies, histórico). Toda consulta com chave
 * válida passa pela auditoria, inclusive as que falham.
 */
@RestController
@Profile("!seed")
@RequestMapping("/api/v1/customers")
@Tag(name = "Customers", description = "Consultas por chave do cliente: ano + tipo do documento + documento")
@SecurityRequirement(name = "bearerAuth")
@ApiResponse(responseCode = "401", description = "Token ausente, expirado, inválido ou de sessão revogada",
        content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
@ApiResponse(responseCode = "403", description = "Token sem o escopo customers:read",
        content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
@ApiResponse(responseCode = "400", description = ApiDocs.INVALID_DATA,
        content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
@ApiResponse(responseCode = "429", description = "Acima do rate limit; tente de novo após o Retry-After",
        content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
@ApiResponse(responseCode = "503", description = "Banco indisponível ou consulta acima do tempo limite",
        content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
public class CustomerController {

    private final FindCompaniesUseCase findCompanies;
    private final FindRecordsByCompanyUseCase findRecords;
    private final SearchRecordsUseCase search;
    private final AuditQueriesUseCase audit;

    public CustomerController(FindCompaniesUseCase findCompanies, FindRecordsByCompanyUseCase findRecords,
                              SearchRecordsUseCase search, AuditQueriesUseCase audit) {
        this.findCompanies = findCompanies;
        this.findRecords = findRecords;
        this.search = search;
        this.audit = audit;
    }

    /** Endpoint 1: empresas ligadas ao cliente. */
    @PostMapping("/companies")
    @Operation(summary = "Empresas ligadas ao cliente",
            description = "Endpoint 1: devolve os CNPJs das empresas ligadas à chave, em ordem crescente. "
                    + "Lista vazia quando a chave não tem vínculos.")
    @ApiResponse(responseCode = "200", description = "CNPJs das empresas ligadas")
    public CompaniesResponse companies(@RequestBody CompaniesRequest request, Principal principal,
                                       HttpServletRequest http) {
        CustomerKey key = request.key();
        return audit.run(Callers.of(principal, http), Action.COMPANIES, key,
                () -> new CompaniesResponse(findCompanies.execute(key)),
                response -> response.companies().size());
    }

    /** Endpoint 2: 1..N registros do cliente para cada empresa informada. */
    @PostMapping("/records")
    @Operation(summary = "Registros do cliente por empresa",
            description = "Endpoint 2: devolve 1 ou N registros do cliente para cada empresa informada (até 100), "
                    + "ordenados por CNPJ. Empresa sem vínculo vem com records vazio, sem ser omitida.")
    @ApiResponse(responseCode = "200", description = "Uma entrada por empresa solicitada")
    public RecordsResponse records(@RequestBody RecordsRequest request, Principal principal, HttpServletRequest http) {
        CustomerKey key = request.key();
        return audit.run(Callers.of(principal, http), Action.RECORDS, key,
                () -> RecordsResponse.from(findRecords.execute(key, request.companies())),
                response -> response.companies().stream().mapToInt(c -> c.records().size()).sum());
    }

    /** Busca paginada com filtros e totais. */
    @PostMapping("/search")
    @Operation(summary = "Busca paginada de registros do cliente",
            description = "Registros da chave ordenados por (empresa, id), com filtros opcionais de empresa, produto e "
                    + "período. Para a próxima página, repita o corpo com cursor = nextCursor. Os totais (registros, "
                    + "empresas e valor) são do filtro inteiro, não só da página.")
    @ApiResponse(responseCode = "200", description = "Uma página de registros e os totais do filtro")
    public SearchResponse search(@RequestBody SearchRequest request, Principal principal, HttpServletRequest http) {
        CustomerKey key = request.key();
        return audit.run(Callers.of(principal, http), Action.SEARCH, key,
                () -> SearchResponse.from(search.execute(request.filter())),
                response -> response.items().size());
    }
}
