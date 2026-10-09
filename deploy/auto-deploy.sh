#!/usr/bin/env bash
# Deploy automático da API na VPS (timer do systemd a cada 2 minutos). Puxa em vez de receber: nenhuma chave da VPS
# fica no GitHub. Só publica um commit novo da main depois que a CI dele passou (testes + portão de cobertura).
#
# Deploy sem queda (rolling): as instâncias novas sobem ao lado das antigas, e as antigas só param depois que todas as
# novas ficaram saudáveis. Durante a troca o Caddy enxerga as duas gerações pelo mesmo nome na rede, então sempre há
# quem responda. Se as novas não ficarem saudáveis em 2 minutos, elas são removidas e as antigas seguem no ar.
set -euo pipefail
cd "$(dirname "$0")/.."
exec 9>/run/vinculos-deploy.lock
flock -n 9 || exit 0

REPO=oliveiravictordev-png/vinculos-api

git fetch -q origin main
atual=$(git rev-parse HEAD)
novo=$(git rev-parse origin/main)
[ "$atual" = "$novo" ] && exit 0

# Resultado da CI para o commit novo (API pública do GitHub, sem token).
ci=$(curl -fsS "https://api.github.com/repos/$REPO/actions/runs?head_sha=$novo&per_page=20" \
  | jq -r '[.workflow_runs[] | select(.name == "CI")][0] | if . == null then "pending" else (.conclusion // "pending") end')
case "$ci" in
  success) ;;
  pending) exit 0 ;;   # CI ainda rodando: tenta de novo no próximo ciclo
  *) echo "commit ${novo:0:7} com CI '$ci': não publicado"; exit 0 ;;
esac

echo "atualizando ${atual:0:7} -> ${novo:0:7}: $(git log -1 --format=%s origin/main)"
git merge -q --ff-only origin/main
cd deploy
replicas=$(docker compose config --format json | jq '.services.api.deploy.replicas // 1')
antigas=$(docker compose ps -q api | sort)

# Instâncias que não existiam antes do deploy.
novas() {
  comm -13 <(echo "$antigas") <(docker compose ps -q api | sort) | sed '/^$/d'
}

novas_saudaveis() {
  for _ in $(seq 1 24); do
    total=$(novas | wc -l)
    healthy=$(novas | xargs -r docker inspect -f '{{.State.Health.Status}}' 2>/dev/null | grep -c '^healthy$' || true)
    [ "$total" -ge "$replicas" ] && [ "$healthy" -eq "$total" ] && return 0
    sleep 5
  done
  return 1
}

docker image inspect vinculos-api:latest >/dev/null 2>&1 && docker tag vinculos-api:latest vinculos-api:anterior
if docker compose build -q api \
    && docker compose up -d --no-deps --no-recreate --scale "api=$((replicas * 2))" api \
    && novas_saudaveis; then
  # O SIGTERM do docker stop dispara o desligamento gracioso do Spring: as requisições em andamento terminam.
  echo "$antigas" | xargs -r docker stop -t 30 >/dev/null
  echo "$antigas" | xargs -r docker rm >/dev/null
  docker image prune -f >/dev/null
  echo "no ar: ${novo:0:7} ($replicas instâncias novas antes de parar as antigas)"
  exit 0
fi

echo "a API nova não ficou saudável: mantendo ${atual:0:7}" >&2
novas | xargs -r docker rm -f >/dev/null
git -C .. reset -q --hard "$atual"
docker image inspect vinculos-api:anterior >/dev/null 2>&1 && docker tag vinculos-api:anterior vinculos-api:latest
# Se não havia instância antiga (ex.: primeira subida), sobe a versão anterior.
[ -n "$antigas" ] || docker compose up -d --no-build api
exit 1
