# Consultas ES|QL para o Elastic Observability

Consultas para montar visualizações e dashboards da `vinculos-api` no Elastic: **Dashboard → Create visualization (query)**, cole a consulta e escolha o tipo de gráfico. Os dados chegam pelo agente EDOT Java (OpenTelemetry), com `service.name = vinculos-api`.

> **Antes de tudo, rode a consulta 0.** Os nomes dos campos de dados OpenTelemetry podem variar conforme a versão do Elastic. Se alguma consulta der `Unknown column`, veja o nome certo na saída da consulta 0. Os equivalentes mais comuns são `log.level` ↔ `severity_text`, `message` ↔ `body.text` e `service.name` ↔ `resource.attributes.service.name`.

## Logs (`FROM logs*,-logstash*,filebeat-*`)

O que a API registra:
- **WARN:** `Slow companies query: 250 ms for CustomerKey[...]` (consulta acima de `app.query.slow-ms`).
- **ERROR:** `MongoDB query failed (code N)`, quando a API responde 503, e `Unexpected error`, quando responde 500.
- **INFO:** subida da aplicação, criação da coleção e do índice.

### 0. Descobrir os campos

```esql
FROM logs*,-logstash*,filebeat-*
| WHERE service.name == "vinculos-api"
| SORT @timestamp DESC
| LIMIT 20
```

### 1. Volume de logs por nível ao longo do tempo (barras empilhadas)

```esql
FROM logs*,-logstash*,filebeat-*
| WHERE service.name == "vinculos-api"
| STATS logs = COUNT(*) BY minute = BUCKET(@timestamp, 1 minute), log.level
| SORT minute
```

### 2. Indicadores: total, avisos e erros (métrica)

```esql
FROM logs*,-logstash*,filebeat-*
| WHERE service.name == "vinculos-api"
| STATS total = COUNT(*),
        warnings = SUM(CASE(log.level == "WARN", 1, 0)),
        errors = SUM(CASE(log.level == "ERROR", 1, 0))
```

### 3. Consultas lentas ao MongoDB por endpoint (tabela)

```esql
FROM logs*,-logstash*,filebeat-*
| WHERE service.name == "vinculos-api" AND message LIKE "Slow * query*"
| GROK message "Slow %{WORD:query} query: %{NUMBER:ms:int} ms"
| STATS slow_queries = COUNT(*), avg_ms = AVG(ms), p95_ms = PERCENTILE(ms, 95), max_ms = MAX(ms) BY query
```

### 4. Consultas lentas ao longo do tempo (linhas)

```esql
FROM logs*,-logstash*,filebeat-*
| WHERE service.name == "vinculos-api" AND message LIKE "Slow * query*"
| GROK message "Slow %{WORD:query} query: %{NUMBER:ms:int} ms"
| STATS slow_queries = COUNT(*), max_ms = MAX(ms) BY minute = BUCKET(@timestamp, 5 minutes), query
| SORT minute
```

### 5. Falhas do banco por código (tabela)

```esql
FROM logs*,-logstash*,filebeat-*
| WHERE service.name == "vinculos-api" AND message LIKE "MongoDB query failed*"
| GROK message "MongoDB query failed \\(code %{INT:code:int}\\)"
| STATS failures = COUNT(*), last_seen = MAX(@timestamp) BY code
```

### 6. Últimos avisos e erros (tabela)

```esql
FROM logs*,-logstash*,filebeat-*
| WHERE service.name == "vinculos-api" AND log.level IN ("WARN", "ERROR")
| KEEP @timestamp, log.level, message
| SORT @timestamp DESC
| LIMIT 50
```

## Métricas de requisições (`FROM traces-*`)

Cada requisição HTTP é um span de servidor. A duração vem em nanossegundos.

### 7. Latência por endpoint: p50, p95 e p99 (tabela)

```esql
FROM traces-*
| WHERE service.name == "vinculos-api" AND kind == "Server"
| EVAL ms = duration / 1000000.0
| STATS requests = COUNT(*), p50 = PERCENTILE(ms, 50), p95 = PERCENTILE(ms, 95), p99 = PERCENTILE(ms, 99) BY name
| SORT requests DESC
```

### 8. Requisições por status HTTP ao longo do tempo (barras empilhadas: 200, 400, 429, 503)

```esql
FROM traces-*
| WHERE service.name == "vinculos-api" AND kind == "Server"
| STATS requests = COUNT(*) BY minute = BUCKET(@timestamp, 1 minute), status = attributes.http.response.status_code
| SORT minute
```

### 9. Rate limit: respostas 429 por minuto (barras)

```esql
FROM traces-*
| WHERE service.name == "vinculos-api" AND attributes.http.response.status_code == 429
| STATS rejected = COUNT(*) BY minute = BUCKET(@timestamp, 1 minute)
| SORT minute
```

### 10. Tempo das consultas ao MongoDB (tabela)

```esql
FROM traces-*
| WHERE service.name == "vinculos-api" AND attributes.db.system.name == "mongodb"
| EVAL ms = duration / 1000000.0
| STATS operations = COUNT(*), p50 = PERCENTILE(ms, 50), p95 = PERCENTILE(ms, 95), p99 = PERCENTILE(ms, 99) BY name
```

Se `attributes.db.system.name` não existir, use `attributes.db.system`. Ele muda conforme a versão da convenção semântica do OpenTelemetry.

### 11. Taxa de erro do servidor (métrica, em %)

```esql
FROM traces-*
| WHERE service.name == "vinculos-api" AND kind == "Server"
| STATS total = COUNT(*), server_errors = SUM(CASE(attributes.http.response.status_code >= 500, 1, 0))
| EVAL error_rate_pct = ROUND(100.0 * server_errors / total, 2)
```

## Gerar dados para os gráficos

```bash
make bench-public    # tráfego contra a demonstração pública, respeitando o rate limit
```

Para ver o rate limit no gráfico 9, dispare uma rajada de mais de 40 requisições do mesmo IP. A API responde 429 ao que passar do limite.
