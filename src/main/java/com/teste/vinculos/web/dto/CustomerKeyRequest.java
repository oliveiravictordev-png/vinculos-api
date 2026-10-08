package com.teste.vinculos.web.dto;

import com.teste.vinculos.domain.CustomerKey;

/** Campos da chave do cliente comuns aos requests; a conversão para o domínio (e sua validação) fica num lugar só. */
public interface CustomerKeyRequest {

    Integer year();

    String documentType();

    String document();

    default CustomerKey key() {
        return CustomerKey.of(year(), documentType(), document());
    }
}
