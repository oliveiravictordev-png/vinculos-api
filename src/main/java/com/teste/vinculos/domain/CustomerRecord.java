package com.teste.vinculos.domain;

import java.math.BigDecimal;
import java.time.Instant;

/** Um dado do cliente vinculado a uma empresa. */
public record CustomerRecord(long id, String company, String product, BigDecimal amount, Instant updatedAt) {
}
