package com.teste.vinculos.web;

/** Balde de fichas: até {@code capacity} requisições de rajada, reabastecido a {@code perSecond} fichas por segundo. */
final class TokenBucket {

    private final double capacity;
    private final double perNano;
    private double tokens;
    private long lastRefill;

    TokenBucket(long capacity, double perSecond, long nowNanos) {
        this.capacity = capacity;
        this.perNano = perSecond / 1e9;
        this.tokens = capacity;
        this.lastRefill = nowNanos;
    }

    /** Consome uma ficha. Devolve 0 se conseguiu; senão, quantos nanossegundos faltam para a próxima ficha. */
    synchronized long tryConsume(long nowNanos) {
        tokens = Math.min(capacity, tokens + (nowNanos - lastRefill) * perNano);
        lastRefill = nowNanos;
        if (tokens >= 1) {
            tokens -= 1;
            return 0;
        }
        return (long) Math.ceil((1 - tokens) / perNano);
    }
}
