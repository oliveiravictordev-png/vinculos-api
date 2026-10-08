package com.teste.vinculos.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class DocumentsTest {

    @ParameterizedTest
    @ValueSource(strings = {"01000000109", "01000000028", "12345678909"})
    void validCpf(String cpf) {
        assertThat(Documents.isValidCpf(cpf)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"12345678900", "11111111111", "0100000010", "0100000010A", ""})
    void invalidCpf(String cpf) {
        assertThat(Documents.isValidCpf(cpf)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"11222333000181", "12ABC34501DE35"})
    void validCnpjIncludingAlphanumeric(String cnpj) {
        assertThat(Documents.isValidCnpj(cnpj)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"11222333000182", "00000000000000", "12ABC34501DE3X", "12abc34501de35", "1122233300018"})
    void invalidCnpj(String cnpj) {
        assertThat(Documents.isValidCnpj(cnpj)).isFalse();
    }

    @Test
    void generatesValidDocuments() {
        assertThat(Documents.generateCpf(10_000_001L)).isEqualTo("01000000109");
        assertThat(Documents.generateCpf(10_000_000L)).isEqualTo("01000000028");
        assertThat(Documents.generateCnpj(112_223_330_001L)).isEqualTo("11222333000181");
    }

    @Test
    void normalizesPunctuationAndLowercase() {
        assertThat(Documents.normalize("010.000.001-09")).isEqualTo("01000000109");
        assertThat(Documents.normalize("12.abc.345/01de-35")).isEqualTo("12ABC34501DE35");
    }

    @Test
    void masksDocument() {
        assertThat(Documents.mask("01000000109")).isEqualTo("010******09");
    }
}
