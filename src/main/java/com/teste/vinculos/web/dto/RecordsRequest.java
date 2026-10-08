package com.teste.vinculos.web.dto;

import com.teste.vinculos.domain.CustomerKey;

import java.util.List;

public record RecordsRequest(Integer year, String documentType, String document, List<String> companies) {

    public CustomerKey key() {
        return CustomerKey.of(year, documentType, document);
    }
}
