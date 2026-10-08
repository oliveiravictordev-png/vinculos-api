package com.teste.vinculos.infrastructure.seed;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("seed")
public record SeedProperties(long totalRecords, long startCustomer, int batchSize, int workers) {

    public SeedProperties {
        if (totalRecords <= 0) {
            totalRecords = 1_000_000_000L;
        }
        if (batchSize <= 0) {
            batchSize = 10_000;
        }
        if (workers <= 0) {
            workers = Runtime.getRuntime().availableProcessors();
        }
    }
}
