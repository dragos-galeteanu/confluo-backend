package com.confluo.webhook;

import com.confluo.webhook.payload.StripeEventPayload;
import com.confluo.webhook.payload.StripeEventPayloadRepository;
import com.stripe.model.Event;
import com.stripe.net.Webhook;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;


/**
 * Applies the events the endpoint recorded.
 *
 * <p>The second half of the webhook design. The endpoint's job is to get a verified event durably
 * stored and answer Stripe quickly; this is what then acts on it, on a timer, with a backoff, and
 * safely across several instances. An event is never lost by a handler failing - it is left
 * {@code FAILED} with a reason and tried again later.</p>
 *
 * <p>Every instance runs the sweep. There is no leader election and no lock table, because the
 * claim locks the rows it takes and skips the ones another instance holds - the same arrangement as
 * {@code OutboxDrainer}.</p>
 *
 * <h2>One transaction per event, not per batch</h2>
 *
 * <p>This is the one place the sweep deliberately differs from {@code OutboxDrainer}, which claims
 * and publishes a whole batch in a single transaction. It can, because publishing is an external
 * call with no database writes to undo. A handler here <strong>does</strong> write - that is its
 * entire purpose - so one failing handler in a shared transaction would either take the whole
 * batch down with it or leave its own half-finished writes committed alongside everyone else's.</p>
 *
 * <p>So each event is claimed, applied and marked in a transaction of its own. A handler that
 * throws rolls back exactly its own work, and the failure is then recorded in a separate
 * transaction - which it has to be, because the first one is being rolled back.</p>
 *
 * <p>The cost is one transaction per event instead of one per batch. At webhook volumes that is
 * nothing, and the alternative is a handler whose partial writes survive its own failure.</p>
 */
@Slf4j
public class StripeEventSweeper {

    /**
     * The longest a failing event waits between attempts.
     *
     * <p>The backoff doubles from the poll interval. A constant rather than a setting, as in
     * {@code OutboxDrainer}: nothing about a deployment makes a different ceiling better, and an
     * event still failing after this many tries needs a person, not more patience.</p>
     */
    static final Duration MAX_BACKOFF = Duration.ofMinutes(5);

    private final StripeEventClaimRepository claims;
    private final StripeEventRepository events;
    private final StripeEventPayloadRepository payloads;
    private final WebhookProperties properties;
    private final TransactionTemplate transaction;
    private final Map<String, StripeEventHandler> handlers;

    /**
     * @param claims the claim, mark and failure queries
     * @param events the ledger, for loading a claimed row
     * @param payloads the archived bodies, which is where the event itself comes from
     * @param properties the batch size, attempt cap and poll interval
     * @param transactionManager the service's transaction manager
     * @param handlerBeans every handler declared in the service; may be empty
     * @throws IllegalStateException if two handlers claim the same event type, which would
     *         otherwise be resolved by whichever Spring happened to list second
     */
    public StripeEventSweeper(
            StripeEventClaimRepository claims,
            StripeEventRepository events,
            StripeEventPayloadRepository payloads,
            WebhookProperties properties,
            PlatformTransactionManager transactionManager,
            List<StripeEventHandler> handlerBeans) {
        this.claims = claims;
        this.events = events;
        this.payloads = payloads;
        this.properties = properties;
        this.transaction = new TransactionTemplate(transactionManager);
        this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.handlers = index(handlerBeans);
    }

    private static Map<String, StripeEventHandler> index(List<StripeEventHandler> beans) {
        Map<String, StripeEventHandler> byType = new HashMap<>();
        for (StripeEventHandler handler : beans) {
            StripeEventHandler existing = byType.put(handler.eventType(), handler);
            if (existing != null) {
                throw new IllegalStateException(
                        "Two handlers claim the Stripe event type %s: %s and %s"
                                .formatted(handler.eventType(),
                                        existing.getClass().getName(), handler.getClass().getName()));
            }
        }
        log.info("Stripe event handlers registered for {} type(s): {}", byType.size(), byType.keySet());
        return byType;
    }

    /**
     * Applies whatever is due, up to the batch size.
     *
     * <p>Nothing escapes. The sweep runs on a timer, so a thrown exception would only be logged by
     * the scheduler and could silently stop the task from being rescheduled.</p>
     */
    public void sweep() {
        try {
            int applied = applyDue(properties.batchSize());
            if (applied > 0) {
                log.debug("Swept {} Stripe event(s)", applied);
            }
        } catch (RuntimeException e) {
            log.warn("The Stripe event sweep failed; it will run again: {}", e.getMessage());
        }
    }

    /**
     * Applies up to {@code batchSize} due events, one transaction each.
     *
     * @param batchSize how many at most
     * @return how many were applied or deliberately ignored
     */
    public int applyDue(int batchSize) {
        int handled = 0;
        for (int i = 0; i < batchSize; i++) {
            if (!applyOne()) {
                break;
            }
            handled++;
        }
        return handled;
    }

    /**
     * Claims one due event and applies it.
     *
     * <p>The {@link AtomicReference} is how the failure path knows which row to blame: the
     * transaction that claimed it is rolled back by the time the exception is caught, so nothing
     * about it can be read from the transaction's own result.</p>
     *
     * @return whether an event was claimed at all, which is how the caller knows to stop
     */
    private boolean applyOne() {
        AtomicReference<StripeEvent> claimed = new AtomicReference<>();
        try {
            Boolean found = transaction.execute(status -> {
                List<UUID> ids = claims.claimDue(1, properties.maxAttempts());
                if (ids.isEmpty()) {
                    return false;
                }
                StripeEvent row = events.findById(ids.get(0)).orElseThrow();
                claimed.set(row);
                apply(row);
                return true;
            });
            return Boolean.TRUE.equals(found);
        } catch (RuntimeException e) {
            StripeEvent row = claimed.get();
            if (row == null) {
                // The claim itself failed, not a handler. Nothing to blame and nothing to record.
                throw e;
            }
            recordFailure(row, e);
            // An event was claimed, so there may well be more behind it.
            return true;
        }
    }

    /**
     * Applies one claimed event, inside the transaction that will mark it.
     *
     * @param row the claimed ledger row
     */
    private void apply(StripeEvent row) {
        StripeEventHandler handler = handlers.get(row.getEventType());
        if (handler == null) {
            // Nothing subscribes to this type. Recorded as a decision rather than left waiting,
            // because an event nothing will ever apply is not a backlog - it is noise in the
            // gauge that is supposed to mean "payments this service has not caught up with".
            claims.markApplied(row.getId(), StripeEventStatus.IGNORED.name());
            log.debug("No handler for Stripe event type {}; ignoring event {}",
                    row.getEventType(), row.getStripeEventId());
            return;
        }

        Event event = reconstruct(row);
        handler.handle(row, event);
        claims.markApplied(row.getId(), StripeEventStatus.PROCESSED.name());
        log.info("Applied Stripe event [id={}, type={}, handler={}]",
                row.getStripeEventId(), row.getEventType(), handler.getClass().getSimpleName());
    }

    /**
     * Rebuilds the event from its archived body.
     *
     * <p>Without verification, deliberately: the signature was checked at the endpoint before the
     * row existed, and the body has not left this platform's own storage since. Re-verifying would
     * also mean this still worked only while the signing secret stayed the same, which would make
     * rotating it retroactively destroy the ability to apply anything received before.</p>
     *
     * @param row the claimed ledger row
     * @return the event as Stripe sent it
     * @throws IllegalStateException if the body was never archived, which leaves nothing to apply
     */
    private Event reconstruct(StripeEvent row) {
        Optional<StripeEventPayload> archived = payloads.findByStripeEventId(row.getStripeEventId());
        if (archived.isEmpty()) {
            // The endpoint's archive is best-effort, so this is reachable: MongoDB was unreachable
            // when the event arrived. Recoverable, but not from here - the body has to be fetched
            // from Stripe or redelivered - so it is a failure with a reason that says so.
            throw new IllegalStateException(
                    "Stripe event %s has no archived body, so there is nothing to apply; "
                            .formatted(row.getStripeEventId())
                            + "replay it from Stripe");
        }
        return Webhook.constructEventWithoutVerification(archived.get().getPayload());
    }

    /**
     * Records a failed attempt, in a transaction of its own.
     *
     * @param row the event that could not be applied
     * @param cause what went wrong
     */
    private void recordFailure(StripeEvent row, RuntimeException cause) {
        int attempts = row.getAttempts() + 1;
        try {
            claims.recordFailure(row.getId(), cause.getMessage(), backoffFor(row));
        } catch (RuntimeException e) {
            // Nothing further can be done: the event stays due and will be claimed again, which is
            // the right failure mode - it is still recorded and still unapplied.
            log.error("Could not record the failure of Stripe event {}: {}",
                    row.getStripeEventId(), e.getMessage());
            return;
        }

        if (attempts >= properties.maxAttempts()) {
            log.error("Giving up on Stripe event {} ({}) after {} attempts; it stays FAILED and "
                            + "will not be retried: {}",
                    row.getStripeEventId(), row.getEventType(), attempts, cause.getMessage());
        } else {
            log.warn("Attempt {} to apply Stripe event {} ({}) failed: {}",
                    attempts, row.getStripeEventId(), row.getEventType(), cause.getMessage());
        }
    }

    private Duration backoffFor(StripeEvent row) {
        long doublings = Math.min(row.getAttempts(), 20);
        Duration backoff = properties.pollInterval().multipliedBy(1L << doublings);
        return backoff.compareTo(MAX_BACKOFF) > 0 ? MAX_BACKOFF : backoff;
    }
}
