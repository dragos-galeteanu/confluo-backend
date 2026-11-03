# Built from the repository root, so the reactor's parent pom is in context:
#   docker build -f platform/Dockerfile -t confluo/platform .
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /build

# The poms first, on their own layer: dependencies are only re-resolved when a pom changes.
# Every module's pom is needed because the root aggregator reads them all.
COPY pom.xml ./
COPY common-core/pom.xml common-core/
COPY common-jpa/pom.xml common-jpa/
COPY common-mongo/pom.xml common-mongo/
COPY common-amqp/pom.xml common-amqp/
COPY eureka-server/pom.xml eureka-server/
COPY platform/pom.xml platform/
COPY notifications/pom.xml notifications/
RUN mvn -B -pl platform -am dependency:go-offline

COPY common-core/src common-core/src
COPY common-jpa/src common-jpa/src
COPY common-mongo/src common-mongo/src
COPY common-amqp/src common-amqp/src
COPY platform/src platform/src
RUN mvn -B -pl platform -am package -DskipTests

FROM eclipse-temurin:17-jre AS runtime

# curl is for the container healthcheck; nothing in the application uses it.
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && useradd --system --uid 1001 --create-home platform

WORKDIR /app
COPY --from=build /build/platform/target/platform.jar app.jar
RUN chown platform:platform app.jar

USER platform
EXPOSE 8081

HEALTHCHECK --interval=15s --timeout=3s --start-period=60s --retries=5 \
    CMD curl -fsS http://localhost:8081/actuator/health || exit 1

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
