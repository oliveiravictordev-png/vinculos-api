package com.teste.vinculos.web.dto;

import com.teste.vinculos.domain.CustomerKey;

public record CompaniesRequest(Integer year, String documentType, String document) {

    public CustomerKey key() {
        return CustomerKey.of(year, documentType, document);
    }
}
