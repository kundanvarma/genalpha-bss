# Runtime image from the HOST-BUILT Spring Boot jar: run `mvn -q package`
# before `docker compose build`. In-container Maven builds made a full-stack
# rebuild take ~30 min and thrash the VM; extracting the prebuilt jar takes
# seconds and keeps the same runtime layering.
FROM eclipse-temurin:17-jre AS build
WORKDIR /build
COPY target/*.jar app.jar
RUN java -Djarmode=layertools -jar app.jar extract --destination target/extracted

# Runtime stage
FROM eclipse-temurin:17-jre
# curl is only for the HEALTHCHECK; the temurin JRE image ships neither curl nor wget.
RUN apt-get update \
 && apt-get install -y --no-install-recommends curl \
 && rm -rf /var/lib/apt/lists/* \
 && useradd --system --uid 1001 --user-group app
WORKDIR /app
# Layers ordered least- to most-volatile so a code-only change rebuilds one layer.
COPY --from=build /build/target/extracted/dependencies/ ./
COPY --from=build /build/target/extracted/spring-boot-loader/ ./
COPY --from=build /build/target/extracted/snapshot-dependencies/ ./
COPY --from=build /build/target/extracted/application/ ./
USER 1001
EXPOSE 8080
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75"
HEALTHCHECK --interval=15s --timeout=3s --start-period=45s --retries=3 \
  CMD curl -fsS http://localhost:8080/actuator/health || exit 1
# exec so the JVM is PID 1 and receives SIGTERM for graceful shutdown.
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS org.springframework.boot.loader.launch.JarLauncher"]
