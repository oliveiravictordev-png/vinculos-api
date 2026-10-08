#!/usr/bin/env bash
# Deploy automático da API na VPS (timer do systemd a cada 2 minutos). Puxa em vez de receber: nenhuma chave da VPS
# fica no GitHub. Só publica um commit novo da main depois que a CI dele passou (testes + portão de cobertura);
# se a API nova não ficar saudável em 2 minutos, volta para a imagem anterior.
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
cd deploy
docker image inspect vinculos-api:latest >/dev/null 2>&1 && docker tag vinculos-api:latest vinculos-api:anterior

saudavel() {
  for _ in $(seq 1 24); do
    [ "$(docker inspect -f '{{.State.Health.Status}}' vinculos-api-1 2>/dev/null)" = healthy ] && return 0
    sleep 5
  done
  return 1
}

git -C .. merge -q --ff-only origin/main
if docker compose up -d --build api && saudavel; then
  docker image prune -f >/dev/null
  echo "no ar: ${novo:0:7}"
  exit 0
fi

echo "a API nova não ficou saudável: voltando para ${atual:0:7}" >&2
git -C .. reset -q --hard "$atual"
if docker image inspect vinculos-api:anterior >/dev/null 2>&1; then
  docker tag vinculos-api:anterior vinculos-api:latest
  docker compose up -d --no-build --force-recreate api
else
  docker compose up -d --build api
fi
exit 1
