# Built from the repository root, so the reactor's parent pom is in context:
#   docker build -f eureka-server/Dockerfile -t confluo/eureka-server .
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /build

# The poms first, on their own layer: dependencies are only re-resolved when a pom changes.
# Every module's pom is needed because the root aggregator reads them all, even though this
# service depends on none of them.
COPY pom.xml ./
COPY common-core/pom.xml common-core/
COPY common-jpa/pom.xml common-jpa/
COPY common-mongo/pom.xml common-mongo/
COPY common-amqp/pom.xml common-amqp/
COPY eureka-server/pom.xml eureka-server/
COPY platform/pom.xml platform/
COPY notifications/pom.xml notifications/
RUN mvn -B -pl eureka-server -am dependency:go-offline

# Only this service's sources: it depends on the parent pom alone, so -am adds nothing else.
COPY eureka-server/src eureka-server/src
RUN mvn -B -pl eureka-server -am package -DskipTests

FROM eclipse-temurin:17-jre AS runtime

# curl is for the container healthcheck; nothing in the application uses it.
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && useradd --system --uid 1001 --create-home eureka

WORKDIR /app
COPY --from=build /build/eureka-server/target/eureka-server.jar app.jar
RUN chown eureka:eureka app.jar

USER eureka
EXPOSE 8761

# Not optional. The platform service waits on this container being healthy before it starts, so
# without a healthcheck it would stay "starting" forever and nothing else would come up.
# The start period is generous because the registry waits out its own first heartbeat interval.
HEALTHCHECK --interval=15s --timeout=3s --start-period=60s --retries=5 \
    CMD curl -fsS http://localhost:8761/actuator/health || exit 1

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
