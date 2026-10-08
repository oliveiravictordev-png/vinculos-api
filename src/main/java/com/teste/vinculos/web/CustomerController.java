package com.teste.vinculos.web;

import com.teste.vinculos.application.FindCompaniesUseCase;
import com.teste.vinculos.application.FindRecordsByCompanyUseCase;
import com.teste.vinculos.web.dto.CompaniesRequest;
import com.teste.vinculos.web.dto.CompaniesResponse;
import com.teste.vinculos.web.dto.RecordsRequest;
import com.teste.vinculos.web.dto.RecordsResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** POST com corpo JSON: CPF/CNPJ não vai para a URL (logs de acesso, proxies, histórico). */
@RestController
@RequestMapping("/api/v1/customers")
@Tag(name = "Customers", description = "Consultas por chave do cliente: ano + tipo do documento + documento")
@ApiResponse(responseCode = "400", description = "Dado inválido (ano fora de 1900-2100, tipo desconhecido, dígito verificador errado)",
        content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
@ApiResponse(responseCode = "503", description = "Banco indisponível ou consulta acima do tempo limite",
        content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
public class CustomerController {

    private final FindCompaniesUseCase findCompanies;
    private final FindRecordsByCompanyUseCase findRecords;

    public CustomerController(FindCompaniesUseCase findCompanies, FindRecordsByCompanyUseCase findRecords) {
        this.findCompanies = findCompanies;
        this.findRecords = findRecords;
    }

    /** Endpoint 1: empresas ligadas ao cliente. */
    @PostMapping("/companies")
    @Operation(summary = "Empresas ligadas ao cliente",
            description = "Endpoint 1: devolve os CNPJs das empresas ligadas à chave, em ordem crescente. "
                    + "Lista vazia quando a chave não tem vínculos.")
    @ApiResponse(responseCode = "200", description = "CNPJs das empresas ligadas")
    public CompaniesResponse companies(@RequestBody CompaniesRequest request) {
        return new CompaniesResponse(findCompanies.execute(request.key()));
    }

    /** Endpoint 2: 1..N registros do cliente para cada empresa informada. */
    @PostMapping("/records")
    @Operation(summary = "Registros do cliente por empresa",
            description = "Endpoint 2: devolve 1 ou N registros do cliente para cada empresa informada (até 100), "
                    + "ordenados por CNPJ. Empresa sem vínculo vem com records vazio, sem ser omitida.")
    @ApiResponse(responseCode = "200", description = "Uma entrada por empresa solicitada")
    public RecordsResponse records(@RequestBody RecordsRequest request) {
        return RecordsResponse.from(findRecords.execute(request.key(), request.companies()));
    }
}
