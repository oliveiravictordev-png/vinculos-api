package com.teste.vinculos.web.dto;

import java.util.List;

/** Resposta do endpoint 1: CNPJs das empresas ligadas à chave, em ordem crescente. */
public record CompaniesResponse(List<String> companies) {
}
