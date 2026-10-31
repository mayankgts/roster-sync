# ---- build stage: compile and package the jar ----
FROM eclipse-temurin:21-jdk AS build
WORKDIR /app
COPY .mvn .mvn
COPY mvnw pom.xml ./
# Windows checkouts can turn mvnw's line endings into CRLF, which breaks it on Linux.
RUN sed -i 's/\r$//' mvnw && chmod +x mvnw && ./mvnw -q -B dependency:go-offline
COPY src src
# Tests need Docker (Testcontainers), which isn't available inside a build. They run in CI / locally.
RUN ./mvnw -q -B package -DskipTests

# ---- runtime stage: JRE only, non-root ----
FROM eclipse-temurin:21-jre
WORKDIR /app
RUN useradd --system --uid 1001 app \
 && mkdir -p /data/reconciliation \
 && chown app /data/reconciliation
COPY --from=build /app/target/roster-sync-*.jar app.jar
USER app
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "app.jar"]