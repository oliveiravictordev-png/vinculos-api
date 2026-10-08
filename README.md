# vinculos-api

API de consulta de vínculos **cliente × empresa** sobre MongoDB, dimensionada para **1 bilhão de registros**, com foco em performance e confiança nos dados.

**Stack:** Java 25 (LTS) · Spring Boot 4.1.1 · MongoDB 8.0 · Caffeine · Log4j2 (assíncrono) · Maven · JUnit 5 + Testcontainers

> **Sobre a versão do Java:** a versão mais recente é o Java 27 (GA em 15/09/2026), mas o Spring Boot 4.1 suporta oficialmente até o Java 26. Por isso o projeto usa o **Java 25, a LTS mais recente**. Para trocar, basta alterar `<java.version>` no `pom.xml`.

## Endpoints

Chave do cliente: **ano + tipo do documento + valor do documento**. Os endpoints usam `POST` com corpo JSON para que o CPF/CNPJ não apareça na URL (logs de acesso, proxies).

### 1. Empresas ligadas ao cliente

```http
POST /api/v1/customers/companies
{ "year": 2024, "documentType": "CPF", "document": "010.000.001-09" }
```
```json
{ "companies": ["10001234000155", "10009153000102"] }
```

### 2. Dados do cliente por empresa

```http
POST /api/v1/customers/records
{ "year": 2024, "documentType": "CPF", "document": "01000000109",
  "companies": ["10001234000155", "10009153000102"] }
```
```json
{ "companies": [
    { "company": "10001234000155", "records": [
        { "id": 15, "product": "CARTAO_CREDITO", "amount": 1520.37, "updatedAt": "2024-03-11T08:21:44.123Z" },
        { "id": 17, "product": "SEGURO", "amount": 88.10, "updatedAt": "2024-09-02T17:03:10.551Z" } ] },
    { "company": "10009153000102", "records": [ ... ] } ] }
```

- Uma entrada por empresa solicitada, ordenada por CNPJ. Uma empresa sem vínculo vem com `records: []`, sem ser omitida.
- No máximo 100 empresas por requisição. Duplicatas e pontuação são normalizadas.
- Os CNPJs acima são ilustrativos. Use o endpoint 1 (ou os exemplos que a carga imprime no log) para obter combinações reais.

Erros seguem a RFC 9457 (`application/problem+json`): **400** para dado inválido (ano fora de 1900–2100, tipo desconhecido, dígito verificador errado) e **503** para timeout ou indisponibilidade do banco.

## Arquitetura

```
com.teste.vinculos
├── domain            regras puras, sem framework
│   ├── CustomerKey         ano + tipo + documento; só existe se válida (normaliza e confere o DV)
│   ├── Documents           validação/geração de CPF e CNPJ (inclui o CNPJ alfanumérico de 2026)
│   ├── CustomerRecord, CompanyRecords, DocumentType, InvalidDataException
│   └── CustomerGateway     porta de saída (interface)
├── application       casos de uso (sem Spring)
│   ├── FindCompaniesUseCase
│   └── FindRecordsByCompanyUseCase
├── infrastructure
│   ├── config              registra os casos de uso como beans
│   ├── mongo               adapter do gateway (driver direto + cache), schema e índice
│   └── seed                gerador determinístico e carga paralela de 1 bilhão de registros
└── web               controller, DTOs (records) e tratamento de erros
```

Convenção: nomes de arquivos, classes, métodos, testes, campos JSON e propriedades em inglês; docstrings e comentários em português. DTOs são `record`s.

## Modelo de dados

Coleção `vinculos`: um documento por registro (cliente × empresa × dado).

| campo | significado | tipo |
|---|---|---|
| `_id` | id do registro (determinístico) | long |
| `a` | ano | int |
| `t` | tipo do documento (`CPF`/`CNPJ`) | string |
| `v` | valor do documento | string |
| `e` | CNPJ da empresa | string |
| `p` | produto | string |
| `s` | valor em centavos | long |
| `u` | atualizado em | date |

**Índice:** `{ a: 1, t: 1, v: 1, e: 1 }`. É o índice pedido (ano + tipo + documento) com a empresa ao final, o que traz dois ganhos:
- **Endpoint 1** vira `distinct("e")` coberto pelo índice (`DISTINCT_SCAN`): nenhum documento é lido do disco.
- **Endpoint 2** resolve o `$in` de empresas dentro do próprio índice e só busca os documentos que de fato vão na resposta.

## Performance

- **Nomes de campo curtos e `_id` long:** em 1 bilhão de documentos, cada byte por documento custa cerca de 1 GB. `_id` long (8 bytes) no lugar de ObjectId (12 bytes) economiza cerca de 4 GB.
- **Compressão zstd** na coleção (WiredTiger).
- **Driver MongoDB direto** (`MongoCollection<Document>` com projeção), sem mapeamento de entidades.
- **Cache Caffeine** (`companies` e `records`) com `sync=true`: requisições simultâneas da mesma chave fazem uma única consulta ao banco. A chave do endpoint 2 é normalizada (CNPJs ordenados e sem duplicatas), o que aumenta o hit rate. É configurável via `CACHE_SPEC`, e as métricas ficam em `/actuator/caches` e `/actuator/metrics/cache.gets`.
- **Virtual threads** no Tomcat para I/O bloqueante, com pool de 200 conexões no Mongo e espera máxima de 2 s por conexão.
- **`maxTimeMS` de 2 s** em toda consulta: uma consulta lenta é abortada no servidor em vez de acumular carga. Consultas acima de 200 ms são logadas em WARN.
- **Log4j2 com AsyncLogger (Disruptor):** a thread da requisição não espera pelo I/O de log.

## Confiança nos dados

- **Validação de entrada no domínio:** os dígitos verificadores de CPF e CNPJ são conferidos (inclusive no CNPJ alfanumérico), e a normalização remove pontuação.
- **Validação de schema no MongoDB** (`$jsonSchema`, `validationLevel: strict`): o banco recusa documentos com tipo ou formato errado, mesmo que venham de fora da aplicação.
- **Read concern `majority` + leitura no primário:** a API só devolve dados confirmados pela maioria do replica set, que não sofrem rollback. Isso é configurado no código, então não depende da connection string.
- **Valores monetários em centavos (long)**, expostos como `BigDecimal`: nenhum erro de ponto flutuante.
- **Carga idempotente:** como o `_id` é determinístico, reexecutar ou retomar a carga nunca duplica registros. Há teste cobrindo isso.
- **Falha explícita:** um timeout ou erro do banco retorna 503, nunca uma resposta parcial.
- **LGPD:** o documento aparece mascarado nos logs (`010******09`).

## Como rodar

Pré-requisitos: **JDK 25**, **Maven 3.9+** e **Docker**.

```bash
# 1. MongoDB (replica set de 1 nó)
docker compose up -d

# 2. Testes (os de integração sobem um MongoDB via Testcontainers; sem Docker eles são ignorados)
mvn test

# 3. API
mvn spring-boot:run
```

### Carga de 1 bilhão de registros

```bash
mvn -DskipTests package
java -jar target/vinculos-api-1.0.0.jar --spring.profiles.active=seed
```

| propriedade | padrão | descrição |
|---|---|---|
| `seed.total-records` | 1000000000 | total de registros (5 por cliente, ou seja, 200 milhões de chaves) |
| `seed.start-customer` | 0 | retoma a carga a partir de um cliente |
| `seed.batch-size` | 10000 | documentos por `insertMany` |
| `seed.workers` | nº de CPUs | threads de inserção |

Exemplo menor para validar o ambiente: `--seed.total-records=10000000`.

Como os dados são gerados:
- Cada cliente tem de 1 a 4 empresas e 5 registros distribuídos entre elas, então há 1 ou N registros por empresa.
- Os anos vão de 2024 a 2026, e cerca de 20% das chaves são CNPJ.
- Os documentos são válidos.
- O log da carga imprime 3 chaves de exemplo para testar os endpoints. Exemplo: `year=2024, CPF 01000000109`.

O índice secundário é criado **ao final** da carga, porque construí-lo uma vez é bem mais barato que mantê-lo a cada insert.

**Ordem de grandeza** (estimativa, depende muito do disco e da CPU):
- Dados: cerca de 120 GB sem compressão, provavelmente entre 40 e 70 GB em disco com zstd.
- Índices: algumas dezenas de GB.
- Tempo: algumas horas em uma máquina com SSD NVMe.

Para manter a latência baixa, a RAM do MongoDB (WiredTiger cache) deve comportar pelo menos o índice `ix_ano_tipo_documento_empresa`.

## Próximos passos sugeridos

- Teste de carga (k6/Gatling) com chaves aleatórias medindo p95/p99, com e sem cache.
- Sharding por `{ a: 1, t: 1, v: 1 }` se o volume crescer além de um nó.
- Endpoint `/actuator/prometheus` (micrometer-registry-prometheus) para observabilidade.
