package com.confluo.webhook;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.actuate.health.Status;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;


/**
 * Says whether this service is actually keeping up with Stripe.
 *
 * <p>Nothing else notices if it is not. A handler that throws on every attempt, a sweep that is
 * wedged, or an event whose body was never archived all leave the service answering HTTP perfectly
 * while quietly not knowing about payments that have been taken. The one number that distinguishes
 * that from a busy service is the <strong>age</strong> of the oldest unapplied event, not the size
 * of the backlog - which is the same conclusion {@code OutboxHealthIndicator} reaches about
 * unpublished events, for the same reason.</p>
 *
 * <p>{@code exhausted} is reported separately and degrades on its own, however young it is. An
 * event that has run out of attempts is not behind, it is abandoned: nothing further will happen to
 * it without a person, so a single one is worth saying out loud even while everything else is
 * current.</p>
 *
 * <p>{@code DEGRADED} rather than {@code DOWN}, as in common-amqp. {@code DOWN} maps to a 503 and
 * would pull an otherwise healthy instance out of rotation, which helps nobody - it can still take
 * webhooks, and refusing them would make Stripe retry and eventually give up. {@code DEGRADED} is
 * not a status Spring Boot maps, so the endpoint still answers 200 and this is for a person or a
 * dashboard reading the details.</p>
 */
@RequiredArgsConstructor
public class StripeEventHealthIndicator implements HealthIndicator {

    /** Not one of Spring Boot's statuses, which is what keeps it out of the HTTP mapping. */
    public static final Status DEGRADED = new Status("DEGRADED", "Stripe events are not being applied");

    private final StripeEventClaimRepository claims;
    private final WebhookProperties properties;

    @Override
    public Health health() {
        Duration staleAfter = properties.staleAfter();
        int maxAttempts = properties.maxAttempts();

        int unapplied;
        int exhausted;
        Optional<Instant> oldest;
        try {
            unapplied = claims.countUnapplied();
            exhausted = claims.countExhausted(maxAttempts);
            oldest = claims.oldestUnapplied();
        } catch (RuntimeException e) {
            // PostgreSQL is the thing this reads; its own indicator will be saying so too.
            return Health.unknown()
                    .withDetail("error", e.getMessage())
                    .withDetail("staleAfter", staleAfter.toString())
                    .build();
        }

        Health.Builder health = Health.up()
                .withDetail("unapplied", unapplied)
                .withDetail("exhausted", exhausted)
                .withDetail("staleAfter", staleAfter.toString());

        if (exhausted > 0) {
            return health.status(DEGRADED).build();
        }
        if (oldest.isEmpty()) {
            return health.build();
        }

        Duration age = Duration.between(oldest.get(), Instant.now());
        health.withDetail("oldestUnappliedAge", age.toString());
        if (age.compareTo(staleAfter) > 0) {
            return health.status(DEGRADED).build();
        }
        return health.build();
    }
}
