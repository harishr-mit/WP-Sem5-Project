# syntax=docker/dockerfile:1
# One image recipe for every MITPass service (docs/DECISIONS.md D17). The build stage compiles the whole reactor once
# (BuildKit shares it between the services, and caches ~/.m2); the runtime stage copies one jar:
#   docker build --build-arg JAR=services/auth-service/target/auth-service.jar -t mitpass/auth-service .
# docker-compose.yml passes the right JAR for each service. Only Docker is needed on the host: no JDK, no Maven.

FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY . .
# Tests ran in CI/`./mvnw verify`; Spotless is a formatting gate, not part of making an image.
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -ntp -q -DskipTests -Dspotless.check.skip=true package

FROM eclipse-temurin:21-jre AS runtime
ARG JAR
# curl is in the image and used by the compose healthchecks. A heap that follows the container's memory limit.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=70 -XX:+ExitOnOutOfMemoryError"
WORKDIR /app
COPY --from=build /src/${JAR} /app/app.jar
RUN useradd --system --no-create-home mitpass
USER mitpass
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
