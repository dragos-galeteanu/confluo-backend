# Built from the repository root, so the reactor's parent pom is in context:
#   docker build -f notifications/Dockerfile -t confluo/notifications .
FROM maven:3.9-eclipse-temurin-17 AS build

# The repository is bind-mounted rather than copied in, which is why this stage names no modules.
# Maven reads the root aggregator and the aggregator lists every module in the reactor, so a
# COPY-based stage has to enumerate all of them - and adding a module then means editing this file
# in every service, which is how this one came to look as though it depended on payments. A mount
# has no list to keep in step.
#
# `rw` makes the mount an overlay: Maven writes target/ into it and those writes are discarded
# with the mount, so the jar is copied out to /out while it still exists. The cache mount is what
# the old "poms first" COPY layer was for - dependencies survive between builds instead of being
# re-resolved - and `sharing=locked` keeps two concurrent builds from writing the same repository.
#
# Needs BuildKit, which Docker Desktop and `docker buildx` use by default; elsewhere set
# DOCKER_BUILDKIT=1.
RUN --mount=type=bind,target=/build,rw \
    --mount=type=cache,target=/root/.m2,sharing=locked \
    cd /build \
    && mvn -B -pl notifications -am package -DskipTests \
    && mkdir -p /out \
    && cp notifications/target/notifications.jar /out/app.jar

FROM eclipse-temurin:17-jre AS runtime

# curl is for the container healthcheck; nothing in the application uses it.
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && useradd --system --uid 1001 --create-home notifications

WORKDIR /app
COPY --from=build /out/app.jar app.jar
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
