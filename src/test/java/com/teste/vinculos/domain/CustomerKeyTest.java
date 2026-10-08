package com.teste.vinculos.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CustomerKeyTest {

    @Test
    void normalizesDocumentAndType() {
        var key = CustomerKey.of(2026, "cpf", "010.000.001-09");

        assertThat(key).isEqualTo(new CustomerKey(2026, DocumentType.CPF, "01000000109"));
    }

    @Test
    void rejectsInvalidData() {
        assertThatThrownBy(() -> CustomerKey.of(null, "CPF", "01000000109")).isInstanceOf(InvalidDataException.class);
        assertThatThrownBy(() -> CustomerKey.of(1800, "CPF", "01000000109")).isInstanceOf(InvalidDataException.class);
        assertThatThrownBy(() -> CustomerKey.of(2026, "RG", "01000000109")).isInstanceOf(InvalidDataException.class);
        assertThatThrownBy(() -> CustomerKey.of(2026, "CPF", "12345678900")).isInstanceOf(InvalidDataException.class);
        assertThatThrownBy(() -> CustomerKey.of(2026, "CNPJ", "01000000109")).isInstanceOf(InvalidDataException.class);
        assertThatThrownBy(() -> CustomerKey.of(2026, "CPF", null)).isInstanceOf(InvalidDataException.class);
    }

    @Test
    void toStringDoesNotExposeDocument() {
        var key = CustomerKey.of(2026, "CPF", "01000000109");

        assertThat(key.toString()).doesNotContain("01000000109").contains("010******09");
    }
}
