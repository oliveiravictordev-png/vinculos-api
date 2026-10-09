package com.teste.vinculos.application;

import com.teste.vinculos.domain.Companies;
import com.teste.vinculos.domain.CompanyRecords;
import com.teste.vinculos.domain.CustomerGateway;
import com.teste.vinculos.domain.CustomerKey;
import com.teste.vinculos.domain.CustomerRecord;
import com.teste.vinculos.domain.InvalidDataException;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** Endpoint 2: 1..N registros do cliente para cada empresa informada. */
public class FindRecordsByCompanyUseCase {

    private final CustomerGateway gateway;

    public FindRecordsByCompanyUseCase(CustomerGateway gateway) {
        this.gateway = gateway;
    }

    /** Devolve uma entrada por empresa solicitada (ordenada por CNPJ), com lista vazia quando não há vínculo. */
    public List<CompanyRecords> execute(CustomerKey key, Collection<String> companies) {
        List<String> normalized = Companies.normalize(companies);
        if (normalized.isEmpty()) {
            throw new InvalidDataException("companies must contain at least 1 CNPJ");
        }
        Map<String, List<CustomerRecord>> byCompany = gateway.findRecords(key, normalized).stream()
                .collect(Collectors.groupingBy(CustomerRecord::company));
        return normalized.stream()
                .map(company -> new CompanyRecords(company, byCompany.getOrDefault(company, List.of())))
                .toList();
    }
}
