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
COPY --from=build /app/target/vinculos-api-1.0.0.jar app.jar
USER app
EXPOSE 8080
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75"
HEALTHCHECK --interval=15s --timeout=3s --start-period=60s --retries=3 \
    CMD curl -fsS http://localhost:8080/actuator/health/readiness || exit 1
# Argumentos extras vão para a aplicação (ex.: --spring.profiles.active=seed).
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar \"$@\"", "--"]
