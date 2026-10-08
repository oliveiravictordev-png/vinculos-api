# Atalhos do projeto. Rode "make" (ou "make help") para listar os comandos.
# Requer: JDK 25, Maven 3.9+ e, para banco/integração/imagem, Docker. No Windows, use WSL ou Git Bash com make.

MVN       ?= mvn
API_URL   ?= http://localhost:8080
RECORDS   ?= 10000000
JAR       := target/vinculos-api-1.0.0.jar
PUBLIC_URL := https://vinculos.212-28-185-69.sslip.io

.DEFAULT_GOAL := help
.PHONY: help mongo-up mongo-down run test verify coverage package seed docker-build health swagger \
        bench bench-public deploy-vps seed-vps clean

help: ## Lista os comandos disponíveis
	@grep -E '^[a-zA-Z_-]+:.*?## ' $(MAKEFILE_LIST) | awk 'BEGIN {FS = ":.*?## "}; {printf "  \033[36m%-14s\033[0m %s\n", $$1, $$2}'

# ---- Ambiente local -------------------------------------------------------------------------------

mongo-up: ## Sobe o MongoDB local (replica set de 1 nó) via Docker
	docker compose up -d

mongo-down: ## Para o MongoDB local (os dados ficam no volume)
	docker compose down

run: ## Sobe a API em http://localhost:8080 (precisa do MongoDB: make mongo-up)
	$(MVN) spring-boot:run

seed: package ## Carga de RECORDS registros (padrão 10 milhões). Ex.: make seed RECORDS=1000000000
	java -jar $(JAR) --spring.profiles.active=seed --seed.total-records=$(RECORDS)

# ---- Testes e qualidade -----------------------------------------------------------------------------

test: ## Testes unitários, web e integração (a integração usa Testcontainers e é pulada sem Docker)
	$(MVN) -B test

verify: ## O mesmo que a CI: testes + portão de cobertura (linhas >= 85%, ramificações >= 75%)
	$(MVN) -B verify

coverage: test ## Roda os testes e mostra onde estão os relatórios
	@echo "Cobertura (JaCoCo): target/site/jacoco/index.html"
	@echo "Testes (Surefire):  target/reports/surefire.html"

bench: ## Benchmark de latência/vazão contra API_URL. Ex.: make bench API_URL=http://localhost:8080
	$(MVN) -B test -Dtest=ApiBenchmark -Dbench.url=$(API_URL) $(BENCH_ARGS)

bench-public: ## Benchmark contra a demonstração pública (clientes 140-160 mi, respeitando o rate limit)
	$(MAKE) bench API_URL=$(PUBLIC_URL) BENCH_ARGS="-Dbench.first-customer=140000000 -Dbench.customers=20000000 \
		-Dbench.samples=300 -Dbench.requests=3000 -Dbench.concurrency=2"

# ---- Build e operação -------------------------------------------------------------------------------

package: ## Gera o jar em target/ (sem rodar os testes)
	$(MVN) -B -DskipTests package

docker-build: ## Gera a imagem Docker da API (vinculos-api:latest)
	docker build -t vinculos-api:latest .

health: ## Health check da API em API_URL (liveness e readiness)
	@curl -fsS $(API_URL)/actuator/health/liveness && echo
	@curl -fsS $(API_URL)/actuator/health/readiness && echo

swagger: ## Mostra os endereços da documentação da API em API_URL
	@echo "Swagger UI: $(API_URL)/swagger-ui.html"
	@echo "OpenAPI:    $(API_URL)/v3/api-docs"

deploy-vps: ## (Na VPS, em /opt/vinculos) atualiza o código e sobe MongoDB + API
	git pull --ff-only
	cd deploy && docker compose up -d --build

seed-vps: ## (Na VPS, em /opt/vinculos) carga de 100 mi registros (clientes 140-160 mi, com a chave do enunciado)
	cd deploy && docker compose up -d mongo && docker compose run --rm api --spring.profiles.active=seed \
		--seed.start-customer=140000000 --seed.total-records=800000000 --seed.workers=2

clean: ## Remove os artefatos de build (target/)
	$(MVN) -B clean
