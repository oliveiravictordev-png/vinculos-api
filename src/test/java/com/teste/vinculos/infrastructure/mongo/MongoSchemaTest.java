package com.teste.vinculos.infrastructure.mongo;

import org.bson.Document;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MongoSchemaTest {

    @Test
    void validatorMatchesTheExpectedJsonSchema() {
        Document expected = Document.parse("""
                { "$jsonSchema": {
                    "bsonType": "object",
                    "required": ["_id", "a", "t", "v", "e", "p", "s", "u"],
                    "properties": {
                      "_id": { "bsonType": "long" },
                      "a":   { "bsonType": "int", "minimum": 1900, "maximum": 2100 },
                      "t":   { "enum": ["CPF", "CNPJ"] },
                      "v":   { "bsonType": "string", "pattern": "^([0-9]{11}|[0-9A-Z]{12}[0-9]{2})$" },
                      "e":   { "bsonType": "string", "pattern": "^[0-9A-Z]{12}[0-9]{2}$" },
                      "p":   { "bsonType": "string" },
                      "s":   { "bsonType": "long" },
                      "u":   { "bsonType": "date" }
                    }
                } }
                """);

        assertThat(MongoSchema.validator().toJson()).isEqualTo(expected.toJson());
    }
}
