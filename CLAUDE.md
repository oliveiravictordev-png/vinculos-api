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
| `domain` | regras de negócio: `CustomerKey`, `Documents`, `Companies`, `RecordFilter`, `RecordCursor`, `AuditEntry`; portas `CustomerGateway`, `RecordSearchGateway`, `QueryAuditLog`, `SessionStore` | Java puro, **sem Spring nem MongoDB**. Toda validação de dado de entrada mora aqui. |
| `application` | casos de uso (`Find*UseCase`, `SearchRecordsUseCase`, `AuditQueriesUseCase`) | **Sem anotações do Spring**; os beans são registrados em `infrastructure/config/UseCaseConfig`. Só dependem de portas do domínio. |
| `infrastructure` | adapters: `mongo` (gateway, auditoria, sessões, schema, índice), `seed` (carga), `monitoring` (alertas), `config` | Detalhes técnicos ficam aqui. Implementa as portas do domínio. |
| `web` | controllers, DTOs, erros, rate limit, ID de correlação, exportação, OpenAPI; `web.security` (JWT, cookies, escopos) | Traduz HTTP ↔ domínio. Não contém regra de negócio. |

- Uma porta nova (ex.: outro banco) é uma interface no `domain` + adapter na `infrastructure`; o caso de uso não muda.
- Bean que depende do servidor web, da autenticação ou da auditoria leva `@Profile("!seed")`: a carga sobe sem servidor web e sem esses segredos.
- Princípios aplicados: **SOLID** (responsabilidade única por classe, dependência de abstrações via portas), **DRY** (abaixo), **12-factor** (configuração por variável de ambiente, logs em stdout, processo sem estado, mesmo build em todo ambiente).

## Convenções de código

- **Idioma:** nomes de arquivos, classes, métodos, variáveis, testes, campos JSON, propriedades de configuração e mensagens de erro/log em **inglês**. **Português** só em Javadoc, comentários, README e neste arquivo.
- **DTOs são `record`s.** Não usamos **Lombok** (records já eliminam o boilerplate) nem **MapStruct** (o mapeamento é pequeno e fica em fábricas estáticas como `RecordsResponse.from(...)`). Só adicione uma dessas bibliotecas se o mapeamento crescer a ponto de justificar, e registre a decisão aqui.
- **Javadoc em português em toda classe pública**, explicando o porquê (decisão, trade-off), não repetindo o que o código já diz. Comentário de linha só onde a intenção não é óbvia.
- **DRY: cada regra tem um único dono.**
  - Nomes de campo do MongoDB: `Fields`.
  - Limites do ano: `CustomerKey.MIN_YEAR`/`MAX_YEAR`. Empresas por consulta: `Companies.MAX`. Página, produto e cursor: `RecordFilter`/`RecordCursor`. Exportação: `SearchRecordsUseCase.MAX_EXPORT_ROWS`.
  - Escopos do token: `Scopes`. Nomes e atributos dos cookies de sessão: `SessionCookies`.
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
  | 400 | dado inválido (mensagem específica) **ou** requisição malformada: rota inexistente, método errado, JSON quebrado, content-type errado, parâmetro de query inválido (mensagem genérica `Invalid request`) |
  | 401 | login inválido (`Invalid username or password`, a mesma para usuário inexistente) ou token ausente, expirado, adulterado ou de sessão revogada (`Authentication required`) |
  | 403 | token válido sem o escopo do endpoint |
  | 429 | acima do rate limit, com `Retry-After` |
  | 500 | erro inesperado, **sem** classe, mensagem ou stack trace |
  | 503 | banco indisponível ou consulta acima do `maxTimeMS` |

  **Nunca responda 404, 405 ou 415**: eles ajudam a mapear a API. Não exponha detalhes internos em mensagem de erro.
- **Limites de entrada:** documento com no máximo 18 caracteres (o CNPJ formatado), no máximo 100 empresas por consulta, página de 1 a 200, produto com até 40 caracteres, cursor com até 64, exportação de até 5.000 linhas, histórico de 1 a 100. Toda entrada nova precisa de um limite.
- **Rate limit** (`RateLimitFilter`): por IP do cliente (visto pelo proxy, `forward-headers-strategy: native`) e global, mais um balde próprio e menor para `/export`. `/actuator` fica fora dele. Os baldes são por instância: com N instâncias, o limite efetivo por IP chega a N vezes o configurado.
- **Paginação por cursor** (`RecordCursor`, opaco em Base64), nunca por `skip`. Lista nova que pode crescer segue o mesmo padrão.
- **Toda consulta a dado de cliente passa por `AuditQueriesUseCase.run`**, inclusive em endpoints novos, para que as falhas também fiquem registradas.

## Dados (MongoDB)

- **Nomes de campo curtos** (`a`, `t`, `v`, `e`, `p`, `s`, `u`), sempre via `Fields`: em 1 bilhão de documentos, cada byte custa ~1 GB.
- **Nunca renomeie** a coleção `vinculos`, os campos nem o índice `ix_ano_tipo_documento_empresa`: há bases carregadas com 1 bilhão de registros.
- **Coleção clusterizada por `_id`** (sem índice `_id` separado), validada por `$jsonSchema` estrito, com zstd. Se o servidor recusar `storageEngine` (Atlas), a criação cai para a compressão padrão.
- **Um índice:** `{a, t, v, e}`. Toda consulta nova na coleção `vinculos` precisa usar esse índice (confira com `explain`, como no `CustomerApiTest`) ou justificar outro índice, medindo o custo em disco e RAM.
- **Coleções auxiliares** `query_audit` e `auth_sessions`: pequenas, com nomes de campo legíveis e **índice TTL** (a auditoria expira por `AUDIT_RETENTION`, as sessões no vencimento). Dado novo que cresce sem limite precisa de TTL. Não renomeie essas coleções nem os índices delas: `MongoIndexes.ensureTtl` ajusta só o tempo.
- **Dinheiro em centavos (`long`)**, exposto como `BigDecimal`. Nunca `double`.
- **Leitura confiável e disponível:** read concern `majority` + `primaryPreferred` (um secundário responde durante a eleição de um novo primário, sem dado que possa sofrer rollback), `maxTimeMS` em toda consulta, `serverSelectionTimeoutMS=5000` na URI e projeção só dos campos necessários.
- **Duas instâncias da API** (`replicas: 2` no compose), sem estado na instância. O deploy é rolling (`auto-deploy.sh`): sobe as novas ao lado das antigas e só para as antigas quando todas as novas estão saudáveis. Não troque por `docker compose up --build`, que recria todas de uma vez e derruba a API.
- **Replica set de 3 nós na VPS** (`mongo` com prioridade 2, `mongo2`, `mongo3`); o `mongo-init` é idempotente. O health check aceita PRIMARY ou SECONDARY, porque um nó que volta de uma falha volta como secundário. Mudança que afete a disponibilidade precisa passar no `ReplicaSetFailoverTest`.
- **Carga (`seed`)** determinística e idempotente (`_id` derivado do cliente); o índice é criado no fim da carga, nunca antes.

## Desempenho

- Cache **Caffeine** em memória com `sync = true` (requisições simultâneas da mesma chave viram uma consulta), um por instância: os dados são só de leitura, então não há invalidação a coordenar. Redis (Caffeine L1 + Redis L2) só quando a taxa de acerto cair por causa da divisão entre instâncias ou for preciso um rate limit exato; a decisão está no README.
- Virtual threads + driver síncrono: não usamos WebFlux/reativo, porque com virtual threads o código bloqueante escala sem a complexidade do reativo. Reavaliar só com evidência de gargalo.
- Pool de conexões dimensionado por ambiente (`maxPoolSize` na URI), JVM com `MaxRAMPercentage` no container.
- Toda otimização vem com medida antes e depois (`ApiBenchmark`, traces no Elastic). Números medidos vão para o README.

## Segurança e LGPD

- **Documento nunca aparece completo em log:** use `CustomerKey.toString()` / `Documents.mask`. O agente OpenTelemetry já troca valores de consulta por `?`.
- **Segredos nunca no Git:** ficam em arquivos `*.env` (ignorados): `atlas-credentials.env`, `elastic-credentials.env` e `deploy/.env` na VPS (senhas, `JWT_PRIVATE_KEY`, `AUDIT_HASH_SECRET`). Exemplo sem segredo em `deploy/.env.example`. Os testes geram as chaves na hora (`TestKeys`): nenhuma chave privada no repositório, nem de teste.
- **JWT RS256** (`JwtKeys`): o `kid` é o thumbprint da chave, e a rotação usa `JWT_PREVIOUS_PUBLIC_KEY`. O claim `type` separa access e refresh, e um nunca vale pelo outro. As sessões ficam no `SessionStore` e podem ser revogadas.
- **Navegador só com cookies** `HttpOnly` + `Secure` + `SameSite=Strict` (`SessionCookies`): nenhum endpoint de sessão devolve token no corpo. Só `POST /auth/token` (Postman, integrações) devolve.
- **Auditoria sem documento completo:** só mascarado + HMAC-SHA256 com `AUDIT_HASH_SECRET`. Um SHA-256 puro de CPF se reverte por força bruta.
- **Actuator público** expõe só `health` (`ACTUATOR_ENDPOINTS`).
- **Cabeçalhos de segurança** ficam no `SecurityHeadersFilter`, que roda antes de tudo: CSP, nosniff, frame DENY, referrer, permissions, HSTS sob HTTPS e `Cache-Control: no-store` em `/api`. Uma página nova servida pela API (como o Swagger) precisa da sua própria CSP ali, sem afrouxar a de `/api`.
- **Dependabot** abre PRs semanais (Maven, Docker, actions). Só faça merge com a CI verde. Atualizações de major (ex.: Spring Boot) se tratam à parte, com leitura das notas de versão.
- Dependências e imagens com **versão fixa** e checksum quando baixadas (ex.: agente EDOT no Dockerfile).
- Login com dois administradores em memória (sem cadastro). O próximo passo é OIDC corporativo (Keycloak/Entra ID): a validação por JWKS e `kid` já está pronta para isso.

## Testes (pirâmide)

| nível | onde | ferramenta |
|---|---|---|
| unidade (maioria) | `domain`, `application`, `TokenBucket`, `MongoSchema`, `JwtKeys`, `OperationalMonitor` | JUnit 5 + AssertJ, fakes em vez de mocks quando possível (`FakeGateway`, `support/InMemory*`) |
| web / contrato HTTP | `ApiErrorsTest`, `SecurityIntegrationTest`, `ExportControllerTest`, `RateLimitFilterTest` | `@WebMvcTest` + `MockMvcTester` (`support/WebSliceConfig`) |
| integração | `CustomerApiTest` | `@SpringBootTest` + Testcontainers (MongoDB real); é ignorado sem Docker |
| falha / alta disponibilidade | `ReplicaSetFailoverTest` | 3 nós via Testcontainers com rede do host (roda só no Linux/CI): mata o primário sob carga |
| carga / desempenho | `ApiBenchmark` | opt-in: só roda com `-Dbench.url=...` |

- Toda regra nova ou bug corrigido entra com teste. Nome do teste descreve o comportamento (`unknownPathIsA400NotA404`).
- `make test` precisa passar antes de qualquer commit.
- **Cobertura:** relatórios em `target/site/jacoco/index.html` (JaCoCo) e `target/reports/surefire.html` (Surefire). O `make verify` (o que a CI roda) **falha** abaixo de 85% de linhas ou 75% de ramificações. Não baixe esses limites para fazer um build passar: escreva o teste.
- **CI:** `.github/workflows/ci.yml` roda `make verify` a cada push, com Docker (integração com MongoDB real), e publica os relatórios.

## Observabilidade

- Agente **EDOT Java** (OpenTelemetry da Elastic) embutido na imagem, ligado só quando `OTEL_EXPORTER_OTLP_ENDPOINT` existe. Envia traces, métricas (JVM, HTTP, Micrometer: cache e pool do Mongo) e logs. Serviço `vinculos-api`.
- Health check: `/actuator/health`, `/liveness`, `/readiness` (o readiness inclui o MongoDB e é usado no `HEALTHCHECK` do Docker).
- Consultas acima de `app.query.slow-ms` são logadas em WARN.
- `X-Request-Id` (`RequestIdFilter`) em toda resposta, em todo log (`%X{requestId}`) e na auditoria.
- **Alertas em dois lugares:**
  - o que só a API vê (401/429/500/503, consultas ao MongoDB acima de `app.query.slow-ms`, queda de acertos do cache) sai do `OperationalMonitor` como log ERROR `ALERT ...`, comparando janelas de contadores acumulados (nunca o valor acumulado em si, nem buckets de histograma, que são uma janela deslizante);
  - o que precisa ser visto de fora (API fora do ar, instâncias, primário, replicação, disco) fica no `deploy/monitor.sh` (timer `vinculos-monitor`).

  Alerta novo entra num dos dois, com o limite documentado no README.

## Como rodar

Os comandos ficam no `Makefile`; `make` lista todos. Comando novo de rotina (rodar, testar, carregar, publicar) entra no `Makefile` com a descrição `## ...`, e não só no README.

```bash
make mongo-up                        # MongoDB local (replica set de 1 nó)
make test                            # testes (a integração precisa de Docker)
make run                             # API em http://localhost:8080 (Swagger em /swagger-ui.html)
make seed RECORDS=10000000           # carga menor
make verify                          # o mesmo que a CI
```

Deploy da demonstração pública: `deploy/docker-compose.yml` na VPS (passo a passo no README, seção "Deploy na VPS"). **O deploy é automático:** o timer `vinculos-deploy` da VPS publica cada commit da `main` cuja CI passou e volta sozinho se a API nova não ficar saudável. Por isso, um push na `main` vai para produção em poucos minutos: nunca faça push direto de algo que não passou em `make verify`. O Caddy que publica a API pertence a outro projeto da VPS (`minha-paroquia`); depois de mudar o `Caddyfile` dele, reinicie o container do Caddy.

## Git

- Mensagens de commit em inglês, no imperativo, com corpo explicando o porquê.
- Commits pequenos e por assunto (uma mudança de comportamento por commit).
- Antes de commitar: `mvn test` verde, README/CLAUDE.md atualizados se a regra mudou, nenhum segredo no diff.
