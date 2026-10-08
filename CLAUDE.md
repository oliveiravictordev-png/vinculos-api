# vinculos-api: guia do projeto

Regras de arquitetura, código, API, testes e operação que todo mundo segue neste repositório (pessoas e agentes). O README explica **o que** o sistema faz; este arquivo diz **como** mexer nele. Ao mudar uma regra daqui, atualize este arquivo no mesmo commit.

## Contexto

Teste técnico: API de vínculos cliente × empresa sobre MongoDB com **1 bilhão de registros**. Chave do cliente: ano + tipo do documento (CPF/CNPJ) + documento. Exemplo do enunciado: `2026 / CPF / 056.858.627-17`, gerado pelo cliente 140.575.883 da carga.

- **Endpoint 1:** `POST /api/v1/customers/companies` devolve as empresas ligadas à chave.
- **Endpoint 2:** `POST /api/v1/customers/records` recebe a chave + `companies[]` e devolve 1 ou N registros por empresa.

O front é um **projeto separado** (`vinculos-web`, TypeScript + Vite, publicado na Vercel). Não coloque código de front aqui.

## Stack

Java 25 · Spring Boot 4.1 (Web MVC, virtual threads) · MongoDB 8.0 (driver direto) · Caffeine · Log4j2 assíncrono · springdoc-openapi · JUnit 5 + AssertJ + Testcontainers · Maven · Docker.

## Arquitetura

Camadas no estilo clean/hexagonal. **A dependência só aponta para dentro:** `web` → `application` → `domain` ← `infrastructure`.

| pacote | papel | regras |
|---|---|---|
| `domain` | regras de negócio: `CustomerKey`, `Documents`, `CustomerRecord`, `CustomerGateway` (porta) | Java puro, **sem Spring nem MongoDB**. Toda validação de dado de entrada mora aqui. |
| `application` | casos de uso (`Find*UseCase`) | **Sem anotações do Spring**; os beans são registrados em `infrastructure/config/UseCaseConfig`. Só dependem de portas do domínio. |
| `infrastructure` | adapters: `mongo` (gateway, schema, índice), `seed` (carga), `config` | Detalhes técnicos ficam aqui. Implementa as portas do domínio. |
| `web` | controller, DTOs, erros, rate limit, OpenAPI | Traduz HTTP ↔ domínio. Não contém regra de negócio. |

- Uma porta nova (ex.: outro banco) é uma interface no `domain` + adapter na `infrastructure`; o caso de uso não muda.
- Princípios aplicados: **SOLID** (responsabilidade única por classe, dependência de abstrações via portas), **DRY** (abaixo), **12-factor** (configuração por variável de ambiente, logs em stdout, processo sem estado, mesmo build em todo ambiente).

## Convenções de código

- **Idioma:** nomes de arquivos, classes, métodos, variáveis, testes, campos JSON, propriedades de configuração e mensagens de erro/log em **inglês**. **Português** só em Javadoc, comentários, README e neste arquivo.
- **DTOs são `record`s.** Não usamos **Lombok** (records já eliminam o boilerplate) nem **MapStruct** (o mapeamento é pequeno e fica em fábricas estáticas como `RecordsResponse.from(...)`). Só adicione uma dessas bibliotecas se o mapeamento crescer a ponto de justificar, e registre a decisão aqui.
- **Javadoc em português em toda classe pública**, explicando o porquê (decisão, trade-off), não repetindo o que o código já diz. Comentário de linha só onde a intenção não é óbvia.
- **DRY: cada regra tem um único dono.**
  - Nomes de campo do MongoDB: `Fields`.
  - Limites do ano: `CustomerKey.MIN_YEAR`/`MAX_YEAR`.
  - Tipos de documento: `DocumentType`.
  - Textos do Swagger: `ApiDocs`.
  - Campos comuns dos requests: `CustomerKeyRequest`.
  - Formato de erro: `ApiExceptionHandler`.
  - O validador `$jsonSchema` é **derivado** dessas constantes, nunca escrito à mão.
  - Repetição trivial (uma conta de tempo, um literal de teste) não precisa virar abstração: DRY é para regras, não para toda linha parecida.
- Injeção por construtor, campos `final`. Nada de `@Autowired` em campo.
- Imutabilidade por padrão (`record`, `List.copyOf`, `final`).

## API

- **Contrato em inglês, versionado em `/api/v1`.** Mudança incompatível vira `/api/v2`; nunca quebre a v1 silenciosamente.
- **POST com corpo JSON** para consultas: CPF/CNPJ nunca vai na URL (logs de acesso, proxies, histórico).
- **Documentação primeiro (API-first):** todo endpoint tem `@Operation`, respostas `@ApiResponse` e exemplos funcionais (a chave do enunciado). Swagger em `/swagger-ui.html`.
- **Erros só em `ApiExceptionHandler`**, no formato RFC 9457 (`application/problem+json`). Status permitidos:

  | status | quando |
  |---|---|
  | 200 | sucesso (lista vazia quando não há vínculo) |
  | 400 | dado inválido (mensagem específica) **ou** requisição malformada: rota inexistente, método errado, JSON quebrado, content-type errado (mensagem genérica `Invalid request`) |
  | 429 | acima do rate limit, com `Retry-After` |
  | 500 | erro inesperado, **sem** classe, mensagem ou stack trace |
  | 503 | banco indisponível ou consulta acima do `maxTimeMS` |

  **Nunca responda 404, 405 ou 415**: eles ajudam a mapear a API. Não exponha detalhes internos em mensagem de erro.
- **Limites de entrada:** documento com no máximo 18 caracteres (o CNPJ formatado), no máximo 100 empresas por consulta. Toda entrada nova precisa de um limite.
- **Rate limit** (`RateLimitFilter`): por IP do cliente (visto pelo proxy, `forward-headers-strategy: native`) e global. `/actuator` fica fora dele.

## Dados (MongoDB)

- **Nomes de campo curtos** (`a`, `t`, `v`, `e`, `p`, `s`, `u`), sempre via `Fields`: em 1 bilhão de documentos, cada byte custa ~1 GB.
- **Nunca renomeie** a coleção `vinculos`, os campos nem o índice `ix_ano_tipo_documento_empresa`: há bases carregadas com 1 bilhão de registros.
- **Coleção clusterizada por `_id`** (sem índice `_id` separado), validada por `$jsonSchema` estrito, com zstd. Se o servidor recusar `storageEngine` (Atlas), a criação cai para a compressão padrão.
- **Um índice:** `{a, t, v, e}`. Toda consulta nova precisa usar esse índice (confira com `explain`) ou justificar outro índice, medindo o custo em disco e RAM.
- **Dinheiro em centavos (`long`)**, exposto como `BigDecimal`. Nunca `double`.
- **Leitura confiável:** primário + read concern `majority`, `maxTimeMS` em toda consulta, projeção só dos campos necessários.
- **Carga (`seed`)** determinística e idempotente (`_id` derivado do cliente); o índice é criado no fim da carga, nunca antes.

## Desempenho

- Cache **Caffeine** em memória com `sync = true` (requisições simultâneas da mesma chave viram uma consulta). Redis só quando houver mais de uma instância da API (Caffeine L1 + Redis L2); a decisão está no README.
- Virtual threads + driver síncrono: não usamos WebFlux/reativo, porque com virtual threads o código bloqueante escala sem a complexidade do reativo. Reavaliar só com evidência de gargalo.
- Pool de conexões dimensionado por ambiente (`maxPoolSize` na URI), JVM com `MaxRAMPercentage` no container.
- Toda otimização vem com medida antes e depois (`ApiBenchmark`, traces no Elastic). Números medidos vão para o README.

## Segurança e LGPD

- **Documento nunca aparece completo em log:** use `CustomerKey.toString()` / `Documents.mask`. O agente OpenTelemetry já troca valores de consulta por `?`.
- **Segredos nunca no Git:** ficam em arquivos `*.env` (ignorados): `atlas-credentials.env`, `elastic-credentials.env` e `deploy/.env` na VPS. Exemplo sem segredo em `deploy/.env.example`.
- **Actuator público** expõe só `health` (`ACTUATOR_ENDPOINTS`).
- Dependências e imagens com **versão fixa** e checksum quando baixadas (ex.: agente EDOT no Dockerfile).
- A API é pública e de leitura, como pede o enunciado; autenticação (API key ou OAuth2) é o próximo passo se ela deixar de ser uma demonstração.

## Testes (pirâmide)

| nível | onde | ferramenta |
|---|---|---|
| unidade (maioria) | `domain`, `application`, `TokenBucket`, `MongoSchema` | JUnit 5 + AssertJ, fakes em vez de mocks quando possível (`FakeGateway`) |
| web / contrato HTTP | `ApiErrorsTest`, `RateLimitFilterTest` | `@WebMvcTest` + `MockMvcTester` |
| integração | `CustomerApiTest` | `@SpringBootTest` + Testcontainers (MongoDB real); é ignorado sem Docker |
| carga / desempenho | `ApiBenchmark` | opt-in: só roda com `-Dbench.url=...` |

- Toda regra nova ou bug corrigido entra com teste. Nome do teste descreve o comportamento (`unknownPathIsA400NotA404`).
- `make test` precisa passar antes de qualquer commit.
- **Cobertura:** relatórios em `target/site/jacoco/index.html` (JaCoCo) e `target/reports/surefire.html` (Surefire). O `make verify` (o que a CI roda) **falha** abaixo de 85% de linhas ou 75% de ramificações. Não baixe esses limites para fazer um build passar: escreva o teste.
- **CI:** `.github/workflows/ci.yml` roda `make verify` a cada push, com Docker (integração com MongoDB real), e publica os relatórios.

## Observabilidade

- Agente **EDOT Java** (OpenTelemetry da Elastic) embutido na imagem, ligado só quando `OTEL_EXPORTER_OTLP_ENDPOINT` existe. Envia traces, métricas (JVM, HTTP, Micrometer: cache e pool do Mongo) e logs. Serviço `vinculos-api`.
- Health check: `/actuator/health`, `/liveness`, `/readiness` (o readiness inclui o MongoDB e é usado no `HEALTHCHECK` do Docker).
- Consultas acima de `app.query.slow-ms` são logadas em WARN.

## Como rodar

Os comandos ficam no `Makefile`; `make` lista todos. Comando novo de rotina (rodar, testar, carregar, publicar) entra no `Makefile` com a descrição `## ...`, e não só no README.

```bash
make mongo-up                        # MongoDB local (replica set de 1 nó)
make test                            # testes (a integração precisa de Docker)
make run                             # API em http://localhost:8080 (Swagger em /swagger-ui.html)
make seed RECORDS=10000000           # carga menor
make verify                          # o mesmo que a CI
```

Deploy da demonstração pública: `deploy/docker-compose.yml` na VPS (passo a passo no README, seção "Deploy na VPS"). O Caddy que publica a API pertence a outro projeto da VPS (`minha-paroquia`); depois de mudar o `Caddyfile` dele, reinicie o container do Caddy.

## Git

- Mensagens de commit em inglês, no imperativo, com corpo explicando o porquê.
- Commits pequenos e por assunto (uma mudança de comportamento por commit).
- Antes de commitar: `mvn test` verde, README/CLAUDE.md atualizados se a regra mudou, nenhum segredo no diff.
