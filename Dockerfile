# syntax=docker/dockerfile:1.7
# Una sola imagen: el frontend construido viaja dentro del JAR y Spring Boot lo sirve.

# ---------- 1. Frontend ----------
FROM node:24-alpine AS frontend
WORKDIR /src/frontend
COPY frontend/package.json frontend/package-lock.json ./
RUN --mount=type=cache,target=/root/.npm npm ci
COPY frontend/ ./
# openapi.json es el contrato del backend; en CI se reemplaza por el recién generado antes de construir.
RUN npm run gen:api && npm run build

# ---------- 2. Backend ----------
FROM maven:3.9-eclipse-temurin-25 AS backend
WORKDIR /src/backend
COPY backend/pom.xml ./
RUN --mount=type=cache,target=/root/.m2 mvn -B -q dependency:go-offline
COPY backend/src ./src
COPY --from=frontend /src/frontend/dist /src/frontend/dist
# Las pruebas ya corrieron en CI contra PostgreSQL real; aquí solo se empaqueta.
RUN --mount=type=cache,target=/root/.m2 mvn -B -q package -DskipTests -Djacoco.skip=true \
 && java -Djarmode=tools -jar target/constructora.jar extract --layers --launcher --destination /extraido

# ---------- 3. Ejecución ----------
FROM eclipse-temurin:25-jre-alpine
RUN addgroup -S -g 10001 app && adduser -S -u 10001 -G app app
WORKDIR /app
# Capas separadas: las dependencias casi nunca cambian y se reutilizan entre versiones.
COPY --from=backend --chown=app:app /extraido/dependencies/ ./
COPY --from=backend --chown=app:app /extraido/spring-boot-loader/ ./
COPY --from=backend --chown=app:app /extraido/snapshot-dependencies/ ./
COPY --from=backend --chown=app:app /extraido/application/ ./
USER 10001:10001
ARG APP_VERSION=local
ENV APP_VERSION=${APP_VERSION} \
    SPRING_PROFILES_ACTIVE=prod \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=70 -XX:+ExitOnOutOfMemoryError"
EXPOSE 8080
HEALTHCHECK --interval=15s --timeout=3s --start-period=60s --retries=4 \
  CMD ["wget", "-q", "--spider", "http://localhost:8080/actuator/health/readiness"]
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
