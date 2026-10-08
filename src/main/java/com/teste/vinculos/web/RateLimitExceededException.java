package com.teste.vinculos.web;

/** Requisição acima do rate limit; vira 429 com Retry-After no {@link ApiExceptionHandler}. */
public class RateLimitExceededException extends RuntimeException {

    private final long retryAfterSeconds;

    public RateLimitExceededException(long retryAfterSeconds) {
        super("Request rate limit exceeded, retry in " + retryAfterSeconds + " s");
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public long retryAfterSeconds() {
        return retryAfterSeconds;
    }
}
