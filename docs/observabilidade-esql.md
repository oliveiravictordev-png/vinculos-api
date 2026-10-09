# Consultas ES|QL para o Elastic Observability

Consultas para montar visualizações e dashboards da `vinculos-api` no Elastic: **Dashboard → Create visualization (query)**, cole a consulta e escolha o tipo de gráfico. Os dados chegam pelo agente EDOT Java (OpenTelemetry), com `service.name = vinculos-api`.

Todas as consultas abaixo foram **executadas contra os dados reais** do projeto (Elastic Serverless, dados OpenTelemetry nativos). As de consultas lentas e de falhas do banco ficam vazias enquanto esses eventos não acontecem; o padrão `GROK` delas foi validado com linhas de exemplo.

## Logs (`FROM logs*,-logstash*,filebeat-*`)

O que a API registra:
- **WARN:** `Slow companies query: 250 ms for CustomerKey[...]` (consulta acima de `app.query.slow-ms`).
- **ERROR:** `MongoDB query failed (code N)`, quando a API responde 503, e `Unexpected error`, quando responde 500.
- **ERROR:** `ALERT ...`, quando o monitor da API passa de um limite (seção [Alertas](#alertas-from-logs)).
- **INFO:** subida da aplicação, criação da coleção e do índice.

### 0. Últimos logs (para conferir os dados)

```esql
FROM logs*,-logstash*,filebeat-*
| WHERE service.name == "vinculos-api"
| KEEP @timestamp, log.level, message, scope.name, trace.id
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

## Requisições (`FROM traces*`)

Cada requisição HTTP é um span de servidor (`kind == "Server"`), com a duração em nanossegundos. O health check (`/actuator/...`) fica de fora para não distorcer os números.

### 7. Latência por endpoint da API: p50, p95 e p99 em ms (tabela)

```esql
FROM traces*
| WHERE service.name == "vinculos-api" AND kind == "Server" AND attributes.http.route LIKE "/api/*"
| EVAL ms = duration / 1000000.0
| STATS requests = COUNT(*), p50 = ROUND(PERCENTILE(ms, 50), 2), p95 = ROUND(PERCENTILE(ms, 95), 2), p99 = ROUND(PERCENTILE(ms, 99), 2) BY endpoint = name
| SORT requests DESC
```

### 8. Requisições por status HTTP ao longo do tempo (barras empilhadas: 200, 400, 429, 503)

```esql
FROM traces*
| WHERE service.name == "vinculos-api" AND kind == "Server" AND NOT attributes.http.route LIKE "/actuator*"
| STATS requests = COUNT(*) BY minute = BUCKET(@timestamp, 1 minute), status = attributes.http.response.status_code
| SORT minute
```

### 9. Rate limit: respostas 429 por minuto (barras)

```esql
FROM traces*
| WHERE service.name == "vinculos-api" AND kind == "Server" AND attributes.http.response.status_code == 429
| STATS rejected = COUNT(*) BY minute = BUCKET(@timestamp, 1 minute)
| SORT minute
```

### 10. Tentativas em rotas inexistentes (segurança; tabela)

A API responde 400 a qualquer rota desconhecida. Volume alto aqui indica alguém tentando mapear a API.

```esql
FROM traces*
| WHERE service.name == "vinculos-api" AND kind == "Server" AND attributes.http.route == "/**"
| STATS attempts = COUNT(*), last_seen = MAX(@timestamp) BY method = attributes.http.request.method
```

### 11. Taxa de erro do servidor em % (métrica)

```esql
FROM traces*
| WHERE service.name == "vinculos-api" AND kind == "Server" AND NOT attributes.http.route LIKE "/actuator*"
| STATS total = COUNT(*), server_errors = SUM(CASE(attributes.http.response.status_code >= 500, 1, 0))
| EVAL error_rate_pct = ROUND(100.0 * server_errors / total, 2)
```

### 12. Tempo das consultas ao MongoDB em ms (tabela)

`distinct` é o endpoint 1 e `find` é o endpoint 2. As operações do health check (`hello`, `listDatabases`) ficam de fora.

```esql
FROM traces*
| WHERE service.name == "vinculos-api" AND attributes.db.system == "mongodb" AND attributes.db.operation IN ("distinct", "find")
| EVAL ms = duration / 1000000.0
| STATS operations = COUNT(*), p50 = ROUND(PERCENTILE(ms, 50), 2), p95 = ROUND(PERCENTILE(ms, 95), 2), p99 = ROUND(PERCENTILE(ms, 99), 2) BY operation = attributes.db.operation
```

## Métricas da aplicação (`FROM metrics*`)

### 13. Taxa de acerto do cache por endpoint em % (métrica/tabela)

`cache.gets` é um contador acumulado desde a subida de cada instância, com as dimensões `cache` (companies/records) e `result` (hit/miss).

```esql
FROM metrics*
| WHERE service.name == "vinculos-api" AND cache.gets IS NOT NULL
| STATS gets = MAX(TO_DOUBLE(cache.gets)) BY service.instance.id, cache, result
| STATS hits = SUM(CASE(result == "hit", gets, 0.0)), total = SUM(gets) BY cache
| EVAL hit_ratio_pct = ROUND(100.0 * hits / total, 1)
```

### 14. Heap da JVM em MB ao longo do tempo (linhas)

O heap vem separado por área de memória: primeiro soma as áreas em cada medição, depois pega o máximo do intervalo.

```esql
FROM metrics*
| WHERE service.name == "vinculos-api" AND attributes.jvm.memory.type == "heap" AND jvm.memory.used IS NOT NULL
| STATS heap = SUM(jvm.memory.used) BY @timestamp, service.instance.id
| STATS heap_mb = ROUND(MAX(heap) / 1048576.0, 1) BY minute = BUCKET(@timestamp, 10 minutes)
| SORT minute
```

## Alertas (`FROM logs*`)

### 15. Alertas da API (regra de alerta e tabela)

O `OperationalMonitor` loga cada alerta em ERROR com o prefixo `ALERT`:
- `ALERT http_status status=401 count=35 window=PT1M`
- `ALERT slow_queries count=12 window=PT1M` (consultas ao MongoDB acima de `app.query.slow-ms`)
- `ALERT cache_hit_drop cache=companies rate=0.30 baseline=0.90 requests=140`

Para virar notificação: **Stack Management → Rules → Create rule → Elasticsearch query**, com tipo ES|QL, a cada 1 minuto, alertando quando a consulta abaixo devolver alguma linha. A ação pode ser e-mail, Slack ou webhook.

```esql
FROM logs*,-logstash*,filebeat-*
| WHERE service.name == "vinculos-api" AND message LIKE "ALERT *"
| GROK message "ALERT %{WORD:alert} %{GREEDYDATA:details}"
| STATS occurrences = COUNT(*), last_seen = MAX(@timestamp) BY alert, details, service.instance.id
| SORT last_seen DESC
```

Consulta executada contra os dados reais em 2026-10-09: uma rajada de 300 respostas 401 na demonstração pública gerou um alerta em cada instância (`status=401 count=158` e `count=142`), que chegaram ao Elastic em menos de um minuto. Os alertas de fora da API (API fora do ar, MongoDB sem primário, replicação atrasada, disco) vêm do `deploy/monitor.sh`, no journal da VPS e no webhook.

## Gerar dados para os gráficos

```bash
make bench-public    # tráfego contra a demonstração pública, respeitando o rate limit
```

Para ver o rate limit no gráfico 9, dispare uma rajada de mais de 40 requisições do mesmo IP. A API responde 429 ao que passar do limite.
