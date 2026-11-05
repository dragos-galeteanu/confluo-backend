package com.confluo.webhook;

import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * How the sweep applies the events the endpoints recorded.
 *
 * <p>Under {@code confluo.webhook} rather than a service's own prefix, because this module owns the
 * sweep and a service that turns webhooks on should not have to know where the setting lives. The
 * signing secrets are not here - they are {@code confluo.stripe}, with the rest of the Stripe
 * credentials, because they are secrets and none of these are.</p>
 *
 * @param enabled whether the sweep runs at all. Off is a legitimate thing to want - a one-off
 *                instance doing a migration should not be competing for events - but off in every
 *                instance means nothing is ever applied, while the endpoints go on recording
 *                perfectly. That failure looks like everything working
 * @param batchSize how many events one sweep applies before leaving the rest to the next. Each is
 *                  its own transaction, so this bounds how long one sweep holds a thread rather
 *                  than how much it locks
 * @param pollInterval how often the sweep runs, and the unit the failure backoff doubles from
 * @param maxAttempts how many times an event is tried before it is left alone. A row that has run
 *                    out stays {@code FAILED} and is no longer claimed, which is what
 *                    {@code countExhausted} reports and what the health indicator degrades on
 * @param staleAfter how old the oldest unapplied event may get before the health indicator says
 *                   so. A backlog seconds old is a busy service; one event ten minutes old is a
 *                   payment nobody has acted on
 */
@Validated
@ConfigurationProperties(prefix = "confluo.webhook")
public record WebhookProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("20") @Min(1) int batchSize,
        @DefaultValue("5s") Duration pollInterval,
        @DefaultValue("10") @Min(1) int maxAttempts,
        @DefaultValue("5m") Duration staleAfter) {
}
