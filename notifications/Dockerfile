# Built from the repository root, so the reactor's parent pom is in context:
#   docker build -f notifications/Dockerfile -t confluo/notifications .
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
RUN mvn -B -pl notifications -am dependency:go-offline

COPY common-core/src common-core/src
COPY common-jpa/src common-jpa/src
COPY common-mongo/src common-mongo/src
COPY common-amqp/src common-amqp/src
COPY notifications/src notifications/src
RUN mvn -B -pl notifications -am package -DskipTests

FROM eclipse-temurin:17-jre AS runtime

# curl is for the container healthcheck; nothing in the application uses it.
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && useradd --system --uid 1001 --create-home notifications

WORKDIR /app
COPY --from=build /build/notifications/target/notifications.jar app.jar
RUN chown notifications:notifications app.jar

USER notifications
EXPOSE 8082

# The liveness group, not /actuator/health, and the difference matters here. This service talks to
# four stores, and only one of them - PostgreSQL, which holds the state machine - makes it unable
# to do its job. Redis being down costs rate limiting, which fails open for transactional messages;
# the broker being down costs events, which the outbox holds until it returns; SMTP being
# unreachable costs the next send attempt, not the service. Watching the aggregate here would mark
# a container unhealthy, and so pull it out of rotation, for any of those.
#
# application.yml defines the group and the service's SecurityConfig permits the path, because
# common-core permits /actuator/health exactly rather than everything beneath it.
HEALTHCHECK --interval=15s --timeout=3s --start-period=60s --retries=5 \
    CMD curl -fsS http://localhost:8082/actuator/health/liveness || exit 1

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
