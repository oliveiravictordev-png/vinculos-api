FROM maven:3.9-eclipse-temurin-25 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn -B -q -ntp dependency:go-offline
COPY src src
RUN mvn -B -q -ntp -DskipTests package

FROM eclipse-temurin:25-jre
# curl só para o HEALTHCHECK; a aplicação roda sem root.
RUN command -v curl >/dev/null || (apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/*) \
    && useradd --system --uid 1001 app
WORKDIR /app
# Agente OpenTelemetry da Elastic (EDOT Java): métricas, traces e logs sem mudar o código. Checksum fixo.
ADD --checksum=sha256:5034fc8f6388cd95575a44b4b5a1679a0108b9da8cacc4daf2db59f56cd4b8bd --chmod=644 \
    https://repo1.maven.org/maven2/co/elastic/otel/elastic-otel-javaagent/1.13.0/elastic-otel-javaagent-1.13.0.jar \
    /app/elastic-otel-javaagent.jar
COPY --from=build /app/target/vinculos-api-1.0.0.jar app.jar
USER app
EXPOSE 8080
ENV JAVA_OPTS="-XX:MaxRAMPercentage=70"
HEALTHCHECK --interval=15s --timeout=3s --start-period=60s --retries=3 \
    CMD curl -fsS http://localhost:8080/actuator/health/readiness || exit 1
# O agente só é carregado quando há destino OTLP configurado (OTEL_EXPORTER_OTLP_ENDPOINT).
# Argumentos extras vão para a aplicação (ex.: --spring.profiles.active=seed).
ENTRYPOINT ["sh", "-c", "AGENT=''; [ -n \"$OTEL_EXPORTER_OTLP_ENDPOINT\" ] && AGENT='-javaagent:/app/elastic-otel-javaagent.jar'; exec java $JAVA_OPTS $AGENT -jar app.jar \"$@\"", "--"]
