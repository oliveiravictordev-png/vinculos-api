package com.teste.vinculos.infrastructure.config;

import com.teste.vinculos.application.FindCompaniesUseCase;
import com.teste.vinculos.application.FindRecordsByCompanyUseCase;
import com.teste.vinculos.domain.CustomerGateway;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Os casos de uso não conhecem o Spring; são registrados aqui. */
@Configuration(proxyBeanMethods = false)
public class UseCaseConfig {

    @Bean
    FindCompaniesUseCase findCompaniesUseCase(CustomerGateway gateway) {
        return new FindCompaniesUseCase(gateway);
    }

    @Bean
    FindRecordsByCompanyUseCase findRecordsByCompanyUseCase(CustomerGateway gateway) {
        return new FindRecordsByCompanyUseCase(gateway);
    }
}
