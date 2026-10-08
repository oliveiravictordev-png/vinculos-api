package com.teste.vinculos.application;

import com.teste.vinculos.domain.CompanyRecords;
import com.teste.vinculos.domain.CustomerGateway;
import com.teste.vinculos.domain.CustomerKey;
import com.teste.vinculos.domain.CustomerRecord;
import com.teste.vinculos.domain.Documents;
import com.teste.vinculos.domain.InvalidDataException;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.stream.Collectors;

/** Endpoint 2: 1..N registros do cliente para cada empresa informada. */
public class FindRecordsByCompanyUseCase {

    public static final int MAX_COMPANIES = 100;

    private final CustomerGateway gateway;

    public FindRecordsByCompanyUseCase(CustomerGateway gateway) {
        this.gateway = gateway;
    }

    /** Devolve uma entrada por empresa solicitada (ordenada por CNPJ), com lista vazia quando não há vínculo. */
    public List<CompanyRecords> execute(CustomerKey key, Collection<String> companies) {
        List<String> normalized = normalize(companies);
        Map<String, List<CustomerRecord>> byCompany = gateway.findRecords(key, normalized).stream()
                .collect(Collectors.groupingBy(CustomerRecord::company));
        return normalized.stream()
                .map(company -> new CompanyRecords(company, byCompany.getOrDefault(company, List.of())))
                .toList();
    }

    // Ordenar e remover duplicatas deixa a consulta e a chave de cache determinísticas.
    private static List<String> normalize(Collection<String> companies) {
        if (companies == null || companies.isEmpty()) {
            throw new InvalidDataException("companies must contain at least 1 CNPJ");
        }
        if (companies.size() > MAX_COMPANIES) {
            throw new InvalidDataException("companies accepts at most " + MAX_COMPANIES + " CNPJs");
        }
        var unique = new TreeSet<String>();
        for (String company : companies) {
            String cnpj = Documents.normalize(company);
            if (!Documents.isValidCnpj(cnpj)) {
                throw new InvalidDataException("invalid company CNPJ: " + cnpj);
            }
            unique.add(cnpj);
        }
        return List.copyOf(unique);
    }
}
