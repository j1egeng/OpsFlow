FROM eclipse-temurin:21-jdk-jammy AS build
WORKDIR /workspace
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN sed -i 's/\r$//' mvnw && chmod +x mvnw
COPY src/ src/
COPY docker/ docker/
# Tests run separately before deployment; private local configuration is excluded.
RUN ./mvnw -B -ntp -DskipTests package \
    && javac -d /healthcheck docker/HealthCheck.java

FROM eclipse-temurin:21-jre-jammy
ARG OPSFLOW_REVISION=local
LABEL org.opencontainers.image.revision=$OPSFLOW_REVISION
WORKDIR /app
RUN groupadd --system opsflow && useradd --system --gid opsflow opsflow
COPY --from=build /workspace/target/opsflow-backend-0.0.1-SNAPSHOT.jar /app/app.jar
COPY --from=build /healthcheck/ /app/healthcheck/
USER opsflow
EXPOSE 8080
HEALTHCHECK --interval=10s --timeout=8s --start-period=60s --retries=12 \
    CMD ["java", "-Xms8m", "-Xmx32m", "-cp", "/app/healthcheck", "HealthCheck"]
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-jar", "/app/app.jar"]
