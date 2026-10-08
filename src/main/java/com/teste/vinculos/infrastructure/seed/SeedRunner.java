package com.teste.vinculos.infrastructure.seed;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** Executa a carga quando a aplicação sobe com o profile "seed" (sem servidor web) e encerra ao terminar. */
@Component
@Profile("seed")
class SeedRunner implements ApplicationRunner {

    private final DataLoader loader;
    private final SeedProperties properties;

    SeedRunner(DataLoader loader, SeedProperties properties) {
        this.loader = loader;
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments args) {
        loader.load(properties.totalRecords(), properties.startCustomer(),
                properties.batchSize(), properties.workers());
    }
}
