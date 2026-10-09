#!/usr/bin/env bash
# Alertas vistos de fora da API (timer do systemd a cada minuto): o que a própria API não consegue avisar porque
# caiu junto ou porque é do host. Os alertas de dentro da API (401/429/5xx, lentidão, cache) saem no log com o
# prefixo ALERT e vão ao Elastic (README, seção "Alertas").
#
#   - API fora do ar (health público pelo Caddy) e menos de 2 instâncias saudáveis
#   - MongoDB sem primário e secundário atrasado (replicação acima de MAX_LAG_SECONDS)
#   - disco acima de MAX_DISK_PERCENT
#
# Cada alerta vai para o journal (journalctl -t vinculos-monitor) e, se ALERT_WEBHOOK_URL estiver no deploy/.env
# (Slack, Discord, ntfy...), para o webhook. Só avisa quando o conjunto de alertas muda (abriu ou resolveu), para
# não mandar a mesma mensagem a cada minuto.
set -uo pipefail
cd "$(dirname "$0")"

HEALTH_URL=${VINCULOS_HEALTH_URL:-https://vinculos.212-28-185-69.sslip.io/actuator/health}
MAX_DISK_PERCENT=${MAX_DISK_PERCENT:-80}
MAX_LAG_SECONDS=${MAX_LAG_SECONDS:-30}
MIN_API_INSTANCES=${MIN_API_INSTANCES:-2}
STATE=/var/lib/vinculos-monitor/alerts
WEBHOOK=$(grep -s '^ALERT_WEBHOOK_URL=' .env | cut -d= -f2- || true)

alerts=()

if ! curl -fsS --max-time 10 "$HEALTH_URL" | grep -q '"status":"UP"'; then
  alerts+=("API fora do ar: $HEALTH_URL")
fi

healthy=$(docker compose ps -q api | xargs -r docker inspect -f '{{.State.Health.Status}}' 2>/dev/null | grep -c '^healthy$' || true)
if [ "$healthy" -lt "$MIN_API_INSTANCES" ]; then
  alerts+=("API com $healthy de $MIN_API_INSTANCES instâncias saudáveis")
fi

# Pergunta ao primeiro nó que responder (qualquer um enxerga o estado do replica set inteiro).
rs=""
for node in mongo mongo2 mongo3; do
  rs=$(docker compose exec -T "$node" mongosh --quiet --eval '
    const s = rs.status();
    const p = s.members.find(m => m.stateStr === "PRIMARY");
    const lag = p ? Math.max(0, ...s.members.filter(m => m.stateStr === "SECONDARY")
      .map(m => (p.optimeDate - m.optimeDate) / 1000)) : -1;
    print((p ? "1" : "0") + " " + Math.round(lag));' </dev/null 2>/dev/null) && break
done
if [ -z "$rs" ]; then
  alerts+=("MongoDB não respondeu em nenhum dos 3 nós")
else
  read -r has_primary lag <<<"$rs"
  [ "$has_primary" = 1 ] || alerts+=("MongoDB sem primário")
  [ "${lag:-0}" -le "$MAX_LAG_SECONDS" ] || alerts+=("replica set atrasado: ${lag}s")
fi

disk=$(df --output=pcent / | tail -1 | tr -dc '0-9')
if [ "$disk" -ge "$MAX_DISK_PERCENT" ]; then
  alerts+=("disco em ${disk}%")
fi

current=$(printf '%s\n' "${alerts[@]}" | sed '/^$/d' | sort)
previous=$(cat "$STATE" 2>/dev/null || true)
for alert in "${alerts[@]}"; do
  logger -p daemon.err -t vinculos-monitor "ALERT $alert"
done

if [ "$current" != "$previous" ]; then
  mkdir -p "$(dirname "$STATE")"
  printf '%s' "$current" >"$STATE"
  if [ -n "$WEBHOOK" ]; then
    message=${current:-"todos os alertas resolvidos"}
    curl -fsS --max-time 10 -H 'Content-Type: application/json' \
      -d "{\"text\": $(printf 'vinculos-api: %s' "$message" | jq -Rs .)}" "$WEBHOOK" >/dev/null \
      || logger -p daemon.err -t vinculos-monitor "webhook de alerta falhou"
  fi
fi

[ ${#alerts[@]} -eq 0 ]
