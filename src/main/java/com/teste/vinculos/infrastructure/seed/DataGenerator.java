package com.teste.vinculos.infrastructure.seed;

import com.teste.vinculos.domain.CustomerKey;
import com.teste.vinculos.domain.DocumentType;
import com.teste.vinculos.domain.Documents;
import com.teste.vinculos.infrastructure.mongo.Fields;
import org.bson.Document;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.function.Consumer;

/**
 * Gera dados sintéticos de forma determinística: o cliente de índice N produz sempre os mesmos documentos,
 * com o mesmo _id. Isso permite reexecutar/retomar a carga sem duplicar nada e conferir o resultado nos testes.
 *
 * <ul>
 *   <li>cliente N: ano = 2024 + N % 3; documento derivado de N / 3 (CPF, ou CNPJ em 1 de cada 5 bases)</li>
 *   <li>cada cliente tem 1 a 4 empresas e exatamente 5 registros distribuídos entre elas (1 ou N por empresa)</li>
 *   <li>_id = N * 5 + j, contíguo de 0 até total - 1</li>
 * </ul>
 */
public final class DataGenerator {

    public static final int RECORDS_PER_CUSTOMER = 5;
    public static final int FIRST_YEAR = 2024;
    public static final int YEAR_COUNT = 3;

    private static final int TOTAL_COMPANIES = 50_000;
    private static final long COMPANY_STEP = 7_919; // primo: garante empresas distintas para o mesmo cliente
    private static final long MS_PER_YEAR = 364L * 24 * 60 * 60 * 1000;
    private static final String[] PRODUCTS = {"CONTA_CORRENTE", "CARTAO_CREDITO", "EMPRESTIMO", "INVESTIMENTO", "SEGURO"};
    private static final String[] COMPANIES = new String[TOTAL_COMPANIES];
    private static final long[] YEAR_START_MS = new long[YEAR_COUNT];

    static {
        for (int i = 0; i < TOTAL_COMPANIES; i++) {
            COMPANIES[i] = Documents.generateCnpj((10_000_000L + i) * 10_000 + 1); // raiz + filial 0001
        }
        for (int i = 0; i < YEAR_COUNT; i++) {
            YEAR_START_MS[i] = LocalDate.of(FIRST_YEAR + i, 1, 1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
        }
    }

    private DataGenerator() {
    }

    public static CustomerKey key(long customer) {
        int year = FIRST_YEAR + (int) (customer % YEAR_COUNT);
        long base = 10_000_000L + customer / YEAR_COUNT;
        return base % 5 == 0
                ? new CustomerKey(year, DocumentType.CNPJ, Documents.generateCnpj((base + 10_000_000L) * 10_000 + 1))
                : new CustomerKey(year, DocumentType.CPF, Documents.generateCpf(base));
    }

    public static List<String> companies(long customer) {
        long h = mix(customer);
        int count = 1 + (int) (h & 3);
        long start = (h >>> 2) % TOTAL_COMPANIES;
        var companies = new ArrayList<String>(count);
        for (int k = 0; k < count; k++) {
            companies.add(COMPANIES[(int) ((start + k * COMPANY_STEP) % TOTAL_COMPANIES)]);
        }
        return companies;
    }

    public static void generate(long customer, Consumer<Document> sink) {
        CustomerKey key = key(customer);
        List<String> companies = companies(customer);
        long yearStart = YEAR_START_MS[key.year() - FIRST_YEAR];
        for (int j = 0; j < RECORDS_PER_CUSTOMER; j++) {
            long id = customer * RECORDS_PER_CUSTOMER + j;
            long h = mix(id);
            sink.accept(new Document(Fields.ID, id)
                    .append(Fields.YEAR, key.year())
                    .append(Fields.TYPE, key.type().name())
                    .append(Fields.DOCUMENT, key.document())
                    .append(Fields.COMPANY, companies.get(j % companies.size()))
                    .append(Fields.PRODUCT, PRODUCTS[(int) ((h >>> 40) % PRODUCTS.length)])
                    .append(Fields.AMOUNT_CENTS, (h >>> 1) % 10_000_000L)
                    .append(Fields.UPDATED_AT, new Date(yearStart + (h >>> 20) % MS_PER_YEAR)));
        }
    }

    // Finalizador do SplitMix64: espalha bem os bits de índices sequenciais.
    static long mix(long x) {
        x += 0x9E3779B97F4A7C15L;
        x = (x ^ (x >>> 30)) * 0xBF58476D1CE4E5B9L;
        x = (x ^ (x >>> 27)) * 0x94D049BB133111EBL;
        return x ^ (x >>> 31);
    }
}
