package com.teste.vinculos;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Ponto de entrada: sobe a API, ou só a carga quando o profile {@code seed} está ativo. */
@SpringBootApplication
@EnableCaching
@EnableScheduling
@ConfigurationPropertiesScan
public class VinculosApplication {

    public static void main(String[] args) {
        SpringApplication.run(VinculosApplication.class, args);
    }
}
