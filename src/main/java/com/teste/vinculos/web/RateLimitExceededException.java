package com.teste.vinculos.web;

/** Requisição acima do rate limit ou login bloqueado; vira 429 com Retry-After no {@link ApiExceptionHandler}. */
public class RateLimitExceededException extends RuntimeException {

    private final long retryAfterSeconds;

    public RateLimitExceededException(long retryAfterSeconds) {
        this("Request rate limit exceeded, retry in " + retryAfterSeconds + " s", retryAfterSeconds);
    }

    private RateLimitExceededException(String message, long retryAfterSeconds) {
        super(message);
        this.retryAfterSeconds = retryAfterSeconds;
    }

    /** Usuário bloqueado por senhas erradas seguidas (a mesma resposta exista o usuário ou não). */
    public static RateLimitExceededException loginLocked(long retryAfterSeconds) {
        return new RateLimitExceededException(
                "Too many failed login attempts, retry in " + retryAfterSeconds + " s", retryAfterSeconds);
    }

    public long retryAfterSeconds() {
        return retryAfterSeconds;
    }
}
