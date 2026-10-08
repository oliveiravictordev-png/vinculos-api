package com.teste.vinculos.web;

import com.teste.vinculos.application.FindCompaniesUseCase;
import com.teste.vinculos.application.FindRecordsByCompanyUseCase;
import com.teste.vinculos.web.dto.CompaniesRequest;
import com.teste.vinculos.web.dto.CompaniesResponse;
import com.teste.vinculos.web.dto.RecordsRequest;
import com.teste.vinculos.web.dto.RecordsResponse;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** POST com corpo JSON: CPF/CNPJ não vai para a URL (logs de acesso, proxies, histórico). */
@RestController
@RequestMapping("/api/v1/customers")
public class CustomerController {

    private final FindCompaniesUseCase findCompanies;
    private final FindRecordsByCompanyUseCase findRecords;

    public CustomerController(FindCompaniesUseCase findCompanies, FindRecordsByCompanyUseCase findRecords) {
        this.findCompanies = findCompanies;
        this.findRecords = findRecords;
    }

    /** Endpoint 1: empresas ligadas ao cliente. */
    @PostMapping("/companies")
    public CompaniesResponse companies(@RequestBody CompaniesRequest request) {
        return new CompaniesResponse(findCompanies.execute(request.key()));
    }

    /** Endpoint 2: 1..N registros do cliente para cada empresa informada. */
    @PostMapping("/records")
    public RecordsResponse records(@RequestBody RecordsRequest request) {
        return RecordsResponse.from(findRecords.execute(request.key(), request.companies()));
    }
}
