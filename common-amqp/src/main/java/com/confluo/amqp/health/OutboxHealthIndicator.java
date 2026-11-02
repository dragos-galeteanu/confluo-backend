package com.confluo.amqp.health;

import com.confluo.amqp.config.AmqpProperties;
import com.confluo.amqp.outbox.OutboxRepository;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.actuate.health.Status;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * Says whether events are actually leaving this service.
 *
 * <p>Nothing else notices if they are not. A broker that is down, a drainer that is wedged, or an
 * event the broker keeps refusing all leave the service answering HTTP perfectly while quietly
 * telling nobody anything - and the one number that distinguishes that from a busy service is the
 * age of the oldest unpublished entry, not the size of the backlog.</p>
 *
 * <p>Reported as {@code DEGRADED} rather than {@code DOWN} on purpose. {@code DOWN} maps to a 503
 * and would take an otherwise healthy service out of rotation, which does not help: it can still
 * serve requests, and removing it only moves the writes somewhere else. {@code DEGRADED} is not a
 * status Spring Boot maps, so the endpoint still answers 200 and the overall status stays
 * {@code UP} - this is for a person or a dashboard looking at the details. A service that does want
 * it to affect a probe can map it through
 * {@code management.endpoint.health.status.http-mapping}.</p>
 */
public class OutboxHealthIndicator implements HealthIndicator {

    /** Not one of Spring Boot's statuses, which is what keeps it out of the HTTP mapping. */
    public static final Status DEGRADED = new Status("DEGRADED", "Events are not being published");

    private final OutboxRepository repository;
    private final AmqpProperties properties;

    /**
     * @param repository the outbox
     * @param properties how old the oldest entry may get
     */
    public OutboxHealthIndicator(OutboxRepository repository, AmqpProperties properties) {
        this.repository = repository;
        this.properties = properties;
    }

    @Override
    public Health health() {
        Duration staleAfter = properties.outbox().staleAfter();
        int pending;
        Optional<Instant> oldest;
        try {
            pending = repository.countPending();
            oldest = repository.oldestPending();
        } catch (RuntimeException e) {
            // The database is the thing this reads; its own health indicator will say so too.
            return Health.unknown()
                    .withDetail("error", e.getMessage())
                    .withDetail("staleAfter", staleAfter.toString())
                    .build();
        }

        Health.Builder health = Health.up()
                .withDetail("pending", pending)
                .withDetail("staleAfter", staleAfter.toString());

        if (oldest.isEmpty()) {
            return health.build();
        }

        Duration age = Duration.between(oldest.get(), Instant.now());
        health.withDetail("oldestPendingAge", age.toString());

        if (age.compareTo(staleAfter) > 0) {
            return health.status(DEGRADED).build();
        }
        return health.build();
    }
}
