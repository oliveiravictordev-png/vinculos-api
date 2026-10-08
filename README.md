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

**Documentação interativa (Swagger):** `/swagger-ui.html`, com a especificação OpenAPI em `/v3/api-docs`. Os exemplos já vêm preenchidos com a chave do enunciado.

**Health check:** `GET /actuator/health`, com `/actuator/health/liveness` (o processo está de pé) e `/actuator/health/readiness` (só fica `UP` quando o MongoDB responde). O container Docker usa o readiness no `HEALTHCHECK`.

Erros seguem a RFC 9457 (`application/problem+json`):
- **400** para dado inválido (ano fora de 1900–2100, tipo desconhecido, dígito verificador errado, documento com mais de 18 caracteres) e para requisição malformada (rota inexistente, método errado, JSON quebrado, content-type errado), esta com a mensagem genérica `Invalid request`. A API nunca responde 404, 405 ou 415, para não ajudar quem tenta mapear rotas.
- **429** acima do rate limit.
- **500** para erro inesperado, sem detalhes internos.
- **503** para timeout ou indisponibilidade do banco.

**Rate limit** em `/api`: até 20 requisições por segundo por IP (rajada de 40) e 300 por segundo na instância (rajada de 600). Acima disso, a API responde 429 com `Retry-After`. O IP considerado é o que o proxy reverso recebeu (`server.forward-headers-strategy: native`), então um `X-Forwarded-For` enviado pelo próprio cliente não burla o limite. Os limites ficam em `app.rate-limit.*`, e o rate limit pode ser desligado com `RATE_LIMIT_ENABLED=false`, por exemplo para o benchmark.

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

Coleção `vinculos`: um documento por registro (cliente × empresa × dado). A coleção é **clusterizada por `_id`**: os documentos ficam gravados na ordem do próprio `_id`, sem um índice `_id` separado.

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
- **Coleção clusterizada por `_id`:** elimina o índice `_id` separado, que em 1 bilhão de registros ocuparia cerca de 32 GB, e reduz a escrita durante a carga.
- **Compressão zstd** na coleção (WiredTiger). Em serviços gerenciados que proíbem essa opção, como o MongoDB Atlas, a coleção é criada com a compressão padrão do serviço (aviso em WARN no log).
- **Driver MongoDB direto** (`MongoCollection<Document>` com projeção), sem mapeamento de entidades.
- **Cache Caffeine** (`companies` e `records`) com `sync=true`: requisições simultâneas da mesma chave fazem uma única consulta ao banco. A chave do endpoint 2 é normalizada (CNPJs ordenados e sem duplicatas), o que aumenta o hit rate. É configurável via `CACHE_SPEC`, e as métricas ficam em `/actuator/caches` e `/actuator/metrics/cache.gets`.
- **Virtual threads** no Tomcat para I/O bloqueante, com pool de 200 conexões no Mongo e espera máxima de 2 s por conexão.
- **`maxTimeMS` de 2 s** em toda consulta: uma consulta lenta é abortada no servidor em vez de acumular carga. Consultas acima de 200 ms são logadas em WARN.
- **Log4j2 com AsyncLogger (Disruptor):** a thread da requisição não espera pelo I/O de log.

### Cache: por que Caffeine e não Redis

O cache fica dentro da API (Caffeine), e não num Redis, porque hoje há **uma única instância** da API:

| | Caffeine (escolhido) | Redis |
|---|---|---|
| Acerto no cache | microssegundos, sem serialização | ~0,5–1 ms (rede + serialização) |
| Requisições simultâneas da mesma chave | `sync=true` já agrupa em uma consulta | exige implementação própria |
| Compartilhado entre instâncias | não | sim |
| Sobrevive a deploy | não | sim |
| Custo operacional | nenhum | mais um serviço e mais RAM |

Os dados não mudam depois da carga, então não há invalidação a coordenar entre instâncias. O cache também só acelera consultas **repetidas**: entre 200 milhões de clientes, uma chave nova quase nunca está em cache, e quem segura a latência nesse caso é o índice do MongoDB.

**Quando trocar:** com várias instâncias da API atrás de um balanceador, o caminho é cache em dois níveis: Caffeine como L1, por instância, e Redis como L2, compartilhado.

**Medido na VPS** (chave fora do cache, requisições disparadas ao mesmo tempo dentro do servidor; a contagem de consultas vem do contador de uso do índice, `$indexStats`):

| requisições simultâneas, mesma chave | respostas | tempo (mín–máx) | consultas ao MongoDB |
|---|---|---|---|
| 8 | 8 × 200 | 8–13 ms | **1** |
| 50 | 50 × 200 | 20–177 ms | **1** |
| 1, repetindo a chave já consultada | 200 | 4 ms | **0** |

Nenhuma requisição falha nem espera o tempo limite: a primeira consulta o banco e as demais recebem o mesmo resultado assim que ele chega.

## Confiança nos dados

- **Validação de entrada no domínio:** os dígitos verificadores de CPF e CNPJ são conferidos (inclusive no CNPJ alfanumérico), e a normalização remove pontuação.
- **Validação de schema no MongoDB** (`$jsonSchema`, `validationLevel: strict`): o banco recusa documentos com tipo ou formato errado, mesmo que venham de fora da aplicação.
- **Read concern `majority` + leitura no primário:** a API só devolve dados confirmados pela maioria do replica set, que não sofrem rollback. Isso é configurado no código, então não depende da connection string.
- **Valores monetários em centavos (long)**, expostos como `BigDecimal`: nenhum erro de ponto flutuante.
- **Carga idempotente:** como o `_id` é determinístico, reexecutar ou retomar a carga nunca duplica registros. Há teste cobrindo isso.
- **Falha explícita:** um timeout ou erro do banco retorna 503, nunca uma resposta parcial.
- **LGPD:** o documento aparece mascarado nos logs (`010******09`).

## Como rodar

Pré-requisitos: **JDK 25**, **Maven 3.9+** e **Docker**. O `Makefile` reúne os comandos; `make` sozinho lista todos.

```bash
make mongo-up     # MongoDB local (replica set de 1 nó) via Docker
make test         # testes (a integração sobe um MongoDB via Testcontainers; sem Docker ela é pulada)
make run          # API em http://localhost:8080
```

| comando | o que faz |
|---|---|
| `make mongo-up` / `make mongo-down` | sobe/para o MongoDB local |
| `make run` | sobe a API (Swagger em `/swagger-ui.html`) |
| `make seed RECORDS=10000000` | carga de dados (padrão 10 milhões; `RECORDS=1000000000` para 1 bilhão) |
| `make test` | testes unitários, web e integração |
| `make verify` | o mesmo que a CI: testes + portão de cobertura |
| `make coverage` | testes + caminhos dos relatórios |
| `make bench API_URL=...` / `make bench-public` | benchmark local / contra a demonstração pública |
| `make health API_URL=...` / `make swagger` | health check / endereços da documentação |
| `make package` / `make docker-build` | jar / imagem Docker |
| `make deploy-vps` / `make seed-vps` | na VPS: atualizar e subir / carregar 100 milhões |
| `make clean` | remove `target/` |

No Windows, os comandos `make` funcionam no WSL ou no Git Bash com `make` instalado. Sem `make`, use os comandos `mvn`/`docker` equivalentes, que estão no próprio `Makefile`.

### Testes e cobertura

O `mvn test` gera dois relatórios: a **cobertura** (JaCoCo) em `target/site/jacoco/index.html` e os **testes** (Surefire) em `target/reports/surefire.html`. A CI (GitHub Actions, `.github/workflows/ci.yml`) roda `make verify` a cada push, com Docker, então o teste de integração usa um MongoDB real. Ela publica os relatórios como artefato (`test-reports`) e mostra a cobertura por pacote no resumo da execução. O `verify` **falha** se a cobertura ficar abaixo de 85% das linhas ou 75% das ramificações.

Resultado na CI (46 testes, 0 falhas):

| pacote | linhas | ramificações |
|---|---|---|
| `application` | 100% | 100% |
| `domain` | 98% | 87% |
| `infrastructure.config` | 100% | 100% |
| `infrastructure.mongo` | 91% | 50% |
| `infrastructure.seed` | 83% | 73% |
| `web` | 97% | 90% |
| `web.dto` | 100% | 100% |
| **total** | **92%** | **82%** |

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
- A chave do exemplo do enunciado (**2026 / CPF / 056.858.627-17**) é gerada pelo cliente 140.575.883, com 4 empresas. Ela existe em qualquer carga a partir de cerca de 703 milhões de registros.

O índice secundário é criado **ao final** da carga, porque construí-lo uma vez é bem mais barato que mantê-lo a cada insert.

Para manter a latência baixa, a RAM do MongoDB (WiredTiger cache) deve comportar pelo menos o índice `ix_ano_tipo_documento_empresa`.

## Resultado com 1 bilhão de registros (medido)

Carga completa executada num notebook com 12 threads, 31 GB de RAM e SSD NVMe de 512 GB. O MongoDB 8.0 rodava como replica set de 1 nó, com 14 GB de cache WiredTiger. A carga usou 8 workers e lotes de 10 mil.

**Carga**

| etapa | tempo |
|---|---|
| inserção de 1.000.000.000 de documentos | 2.189 s (~36 min, ~457 mil docs/s) |
| criação do índice `{a, t, v, e}` | 2.492 s (~42 min) |
| **total** | **4.682 s (~78 min)** |

**Tamanho**

| | medido |
|---|---|
| documentos na coleção | 1.000.000.000 (118 bytes em média) |
| dados sem compressão | 110,8 GB |
| dados em disco (zstd) | **23,2 GB** (4,8× de compressão) |
| índice `ix_ano_tipo_documento_empresa` | **15,2 GB**; não há índice `_id` porque a coleção é clusterizada |
| pasta do MongoDB (inclui journal e oplog) | 44,1 GB |

**Latência e vazão** (`ApiBenchmark` com 2.000 chaves aleatórias entre os 200 milhões de clientes; API e benchmark na mesma máquina; rate limit desligado):

| endpoint | cache da API | p50 | p95 | p99 |
|---|---|---|---|---|
| 1 (`/companies`) | sem cache | 3,1 ms | 4,9 ms | 6,1 ms |
| 1 (`/companies`) | com cache | 0,6 ms | 1,5 ms | 2,2 ms |
| 2 (`/records`) | sem cache | 2,2 ms | 3,5 ms | 4,3 ms |
| 2 (`/records`) | com cache | 0,4 ms | 0,6 ms | 1,1 ms |

Vazão do endpoint 1 com chaves sempre novas (sem acerto de cache) e 64 requisições simultâneas: **3.973 req/s**, sem nenhum erro em 50 mil requisições.

No endpoint 2, "sem cache" refere-se ao cache da API. As mesmas chaves tinham acabado de passar pelo endpoint 1, então parte das páginas do índice já estava na memória do MongoDB.

A chave do enunciado (2026 / CPF / 056.858.627-17) responde com 4 empresas sobre o bilhão.

### Benchmark

Com a API rodando sobre a base carregada:

```bash
make bench API_URL=http://localhost:8080
# ou: mvn test -Dtest=ApiBenchmark -Dbench.url=http://localhost:8080
```

Sorteia 2.000 chaves entre os clientes carregados e mede p50/p95/p99 dos dois endpoints, com e sem o cache da API, além da vazão do endpoint 1 com 64 requisições simultâneas. Opções: `-Dbench.first-customer` e `-Dbench.customers` (faixa de clientes carregados; padrão a partir de 0, com 200 milhões), `-Dbench.samples`, `-Dbench.concurrency` e `-Dbench.requests`. Sem `bench.url`, o teste é ignorado.

## Deploy na VPS

A demonstração pública roda numa VPS compartilhada: `deploy/docker-compose.yml` sobe o MongoDB (2 CPUs, 3 GB) e a API (2 CPUs, 1 GB), sem publicar portas. A API entra na rede `deploy_default` do Caddy que já atende 80/443 na VPS, e o Caddy a publica com HTTPS automático.

```bash
git clone https://github.com/oliveiravictordev-png/vinculos-api.git /opt/vinculos && cd /opt/vinculos/deploy
docker compose up -d mongo
# 100 milhões de registros: clientes 140.000.000 a 159.999.999, faixa que inclui a chave do enunciado
docker compose run --rm api --spring.profiles.active=seed --seed.start-customer=140000000 --seed.total-records=800000000 --seed.workers=2
docker compose up -d --build api
```

A carga vem antes da API porque a API, ao subir, cria o índice. Assim ele é criado uma vez só, no fim da carga.

### Observabilidade (Elastic + OpenTelemetry)

A imagem traz o agente OpenTelemetry da Elastic (EDOT Java), que só é carregado quando `OTEL_EXPORTER_OTLP_ENDPOINT` está definido. Para ligar, copie `deploy/.env.example` para `deploy/.env` na VPS e preencha o endpoint OTLP e a chave de API do Elastic Observability. Sem mudar o código, o agente envia:

- **Traces:** cada requisição HTTP, com a consulta ao MongoDB como span filho. Os valores das consultas são substituídos por `?`, então nenhum CPF/CNPJ sai da API.
- **Métricas:** JVM (heap, GC, threads, CPU) e latência e volume das requisições HTTP, a cada 15 s.
- **Logs:** os do Log4j2, com o documento já mascarado.

No Elastic, o serviço aparece como `vinculos-api`, no ambiente `vps-demo`.

Para gerar tráfego contra a demonstração pública (a VPS tem os clientes de 140 a 160 milhões):

```bash
mvn test -Dtest=ApiBenchmark -Dbench.url=https://vinculos.212-28-185-69.sslip.io \
  -Dbench.first-customer=140000000 -Dbench.customers=20000000 -Dbench.samples=300 -Dbench.requests=3000 -Dbench.concurrency=2
```

Com o rate limit ligado, um único IP passa de 20 requisições por segundo com poucas conexões simultâneas. Por isso, contra a VPS, use `-Dbench.concurrency=2`. Para medir a vazão máxima, rode o benchmark contra uma instância com `RATE_LIMIT_ENABLED=false`.

## Próximos passos sugeridos

- Sharding por `{ a: 1, t: 1, v: 1 }` se o volume crescer além de um nó.
- Redis como cache L2 compartilhado, mantendo o Caffeine como L1, quando a API tiver mais de uma instância (ver "Cache: por que Caffeine e não Redis").
- Endpoint `/actuator/prometheus` (micrometer-registry-prometheus) para observabilidade.
