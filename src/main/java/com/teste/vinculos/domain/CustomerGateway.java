package com.teste.vinculos.domain;

import java.util.List;

/** Porta de saída para a base de vínculos. */
public interface CustomerGateway {

    /** CNPJs das empresas ligadas à chave, em ordem crescente. */
    List<String> findCompanies(CustomerKey key);

    /** Registros da chave restritos às empresas informadas (lista já normalizada, ordenada e sem duplicatas). */
    List<CustomerRecord> findRecords(CustomerKey key, List<String> companies);
}
