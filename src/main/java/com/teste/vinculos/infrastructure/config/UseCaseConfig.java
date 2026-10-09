package com.teste.vinculos.infrastructure.config;

import com.teste.vinculos.application.AuditQueriesUseCase;
import com.teste.vinculos.application.FindCompaniesUseCase;
import com.teste.vinculos.application.FindRecordsByCompanyUseCase;
import com.teste.vinculos.application.SearchRecordsUseCase;
import com.teste.vinculos.domain.CustomerGateway;
import com.teste.vinculos.domain.QueryAuditLog;
import com.teste.vinculos.domain.RecordSearchGateway;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import java.time.Clock;

/** Os casos de uso não conhecem o Spring; são registrados aqui. */
@Configuration(proxyBeanMethods = false)
public class UseCaseConfig {

    /** Relógio único da aplicação (UTC): os testes trocam por um relógio fixo. */
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    FindCompaniesUseCase findCompaniesUseCase(CustomerGateway gateway) {
        return new FindCompaniesUseCase(gateway);
    }

    @Bean
    FindRecordsByCompanyUseCase findRecordsByCompanyUseCase(CustomerGateway gateway) {
        return new FindRecordsByCompanyUseCase(gateway);
    }

    @Bean
    SearchRecordsUseCase searchRecordsUseCase(RecordSearchGateway gateway) {
        return new SearchRecordsUseCase(gateway);
    }

    @Bean
    @Profile("!seed")
    AuditQueriesUseCase auditQueriesUseCase(QueryAuditLog log, Clock clock) {
        return new AuditQueriesUseCase(log, clock);
    }
}
