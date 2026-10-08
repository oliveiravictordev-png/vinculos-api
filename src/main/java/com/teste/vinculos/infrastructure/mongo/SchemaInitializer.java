package com.teste.vinculos.infrastructure.mongo;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** Na subida da API garante coleção e índice (no-op quando já existem). Na carga, o índice é criado ao final. */
@Component
@Profile("!seed")
class SchemaInitializer implements ApplicationRunner {

    private final MongoSchema schema;

    SchemaInitializer(MongoSchema schema) {
        this.schema = schema;
    }

    @Override
    public void run(ApplicationArguments args) {
        schema.ensureCollection();
        schema.ensureIndex();
    }
}
