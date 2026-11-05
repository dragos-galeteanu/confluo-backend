# Built from the repository root, so the reactor's parent pom is in context:
#   docker build -f payments/Dockerfile -t confluo/payments .
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
    && mvn -B -pl payments -am package -DskipTests \
    && mkdir -p /out \
    && cp payments/target/payments.jar /out/app.jar

FROM eclipse-temurin:17-jre AS runtime

# curl is for the container healthcheck; nothing in the application uses it.
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && useradd --system --uid 1001 --create-home payments

WORKDIR /app
COPY --from=build /out/app.jar app.jar
RUN chown payments:payments app.jar

USER payments
EXPOSE 8083

# The liveness group rather than /actuator/health, for the reason notifications does the same: of
# the four stores this service talks to, only PostgreSQL makes it unable to do its job. It holds
# the webhook ledger, which is what decides whether an event has already been applied, and the
# mirrored state every answer about money is read from. MongoDB down costs the archived copy of a
# webhook body, not the webhook; Redis down costs a lock that PostgreSQL's unique constraint can
# also enforce; the broker down costs events, which the outbox holds until it returns; and Stripe
# being unreachable costs the next API call, not the service - the webhooks still have to be
# accepted, because Stripe will keep retrying them for days and dropping them is how state
# silently diverges.
#
# application.yml defines the group and the service's SecurityConfig permits the path, because
# common-core permits /actuator/health exactly rather than everything beneath it.
HEALTHCHECK --interval=15s --timeout=3s --start-period=60s --retries=5 \
    CMD curl -fsS http://localhost:8083/actuator/health/liveness || exit 1

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
