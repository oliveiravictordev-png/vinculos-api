package com.teste.vinculos.domain;

import java.util.List;

/** Registros do cliente em uma empresa (lista vazia quando não há vínculo). */
public record CompanyRecords(String company, List<CustomerRecord> records) {
}
