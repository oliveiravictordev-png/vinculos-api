package com.teste.vinculos.application;

import com.teste.vinculos.domain.CustomerGateway;
import com.teste.vinculos.domain.CustomerKey;

import java.util.List;

/** Endpoint 1: empresas ligadas ao cliente. */
public class FindCompaniesUseCase {

    private final CustomerGateway gateway;

    public FindCompaniesUseCase(CustomerGateway gateway) {
        this.gateway = gateway;
    }

    public List<String> execute(CustomerKey key) {
        return gateway.findCompanies(key);
    }
}
