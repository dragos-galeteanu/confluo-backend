package com.confluo.amqp.outbox;

import com.confluo.amqp.config.AmqpProperties;
import com.confluo.amqp.metrics.AmqpMetrics;
import com.confluo.amqp.publish.OutboxEntryWritten;
import com.confluo.amqp.publish.RabbitEventSender;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.List;

/**
 * Takes entries out of the outbox and publishes them, from two triggers.
 *
 * <p>The <strong>nudge</strong> runs immediately after the transaction that wrote an entry commits,
 * so the usual case costs no waiting at all. The <strong>sweep</strong> runs on a timer and is what
 * makes the design correct rather than merely quick: it picks up anything the nudge could not send
 * because the process died, the broker was down, or the publish was refused.</p>
 *
 * <p>Every instance runs both. There is no leader election and no lock table, because
 * {@code claimPending} locks the rows it takes and skips the ones another instance holds.</p>
 *
 * <p>Each drain runs in a transaction of its own, never the caller's. The nudge fires while the
 * committed business transaction is still bound to the thread, and joining it would mean claiming
 * rows in a transaction that has already committed - so the locks would be released at once and two
 * drainers could publish the same event.</p>
 */
@Slf4j
public class OutboxDrainer implements OutboxPublisher {

    /**
     * The longest a failing entry waits between attempts.
     *
     * <p>The backoff doubles from the poll interval, which matters for an entry that keeps being
     * refused - an unroutable event, say - rather than for a broker outage, where retrying every
     * second is cheap and gets the backlog out as soon as it recovers. A constant rather than a
     * setting because nothing about a deployment makes a different value better.</p>
     */
    static final Duration MAX_BACKOFF = Duration.ofMinutes(1);

    private final OutboxRepository repository;
    private final RabbitEventSender sender;
    private final AmqpProperties properties;
    private final AmqpMetrics metrics;
    private final TransactionTemplate transaction;

    /**
     * @param repository the outbox
     * @param sender what publishes one entry
     * @param properties the batch size, the attempt cap and the poll interval
     * @param metrics where the published and failed counts go
     * @param transactionManager the service's transaction manager
     */
    public OutboxDrainer(
            OutboxRepository repository,
            RabbitEventSender sender,
            AmqpProperties properties,
            AmqpMetrics metrics,
            PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.sender = sender;
        this.properties = properties;
        this.metrics = metrics;
        this.transaction = new TransactionTemplate(transactionManager);
        this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * Publishes as soon as the writing transaction has committed.
     *
     * <p>Nothing here may escape. This runs as part of completing the caller's transaction, so a
     * thrown exception would surface in the request that produced the event - reporting a failure
     * for a change that has in fact been saved. The sweep exists precisely so that failing here is
     * survivable, which makes swallowing the error the correct behaviour rather than a shortcut.</p>
     *
     * <p>It drains whatever is due rather than only the entry just written, which keeps publishing
     * in the order the events happened.</p>
     *
     * @param event the entry that was written
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onEntryWritten(OutboxEntryWritten event) {
        try {
            publishPending(properties.outbox().batchSize());
        } catch (RuntimeException e) {
            log.debug("Could not publish event {} straight after commit; the sweep will retry it: {}",
                    event.entryId(), e.getMessage());
        }
    }

    /**
     * Publishes anything the nudge did not.
     *
     * <p>Not annotated with {@code @Scheduled}. That reads the interval as a raw string, and
     * {@code @Scheduled} accepts only a plain number of milliseconds or an ISO-8601 duration - so a
     * perfectly ordinary {@code poll-interval: 1s} would stop the service at startup with "cannot
     * parse into long". {@code OutboxConfiguration} registers this against the already-bound
     * {@code Duration} instead, which accepts every form Spring Boot does.</p>
     */
    public void sweep() {
        try {
            int published = publishPending(properties.outbox().batchSize());
            if (published > 0) {
                log.debug("Swept {} event(s) out of the outbox", published);
            }
        } catch (RuntimeException e) {
            log.warn("The outbox sweep failed; it will run again: {}", e.getMessage());
        }
    }

    @Override
    public int publishPending(int batchSize) {
        Integer published = transaction.execute(status -> {
            List<OutboxEntry> claimed =
                    repository.claimPending(batchSize, properties.outbox().maxAttempts());
            int sent = 0;
            for (OutboxEntry entry : claimed) {
                if (publish(entry)) {
                    sent++;
                }
            }
            return sent;
        });
        return published == null ? 0 : published;
    }

    /**
     * Publishes one entry, recording the outcome either way.
     *
     * <p>A failure is caught rather than propagated so that one bad entry does not abandon the rest
     * of the batch - and because the entry's own row is where the failure belongs.</p>
     *
     * @param entry the claimed entry
     * @return whether it went out
     */
    private boolean publish(OutboxEntry entry) {
        try {
            sender.send(entry);
            repository.markPublished(entry.id());
            metrics.published();
            return true;
        } catch (RuntimeException e) {
            int attempts = entry.attempts() + 1;
            repository.recordFailure(entry.id(), e.getMessage(), backoffFor(entry));
            metrics.failed();
            if (attempts >= properties.outbox().maxAttempts()) {
                log.error("Giving up on event {} ({}) after {} attempts; it stays in the outbox: {}",
                        entry.id(), entry.eventType(), attempts, e.getMessage());
            } else {
                log.warn("Attempt {} to publish event {} ({}) failed: {}",
                        attempts, entry.id(), entry.eventType(), e.getMessage());
            }
            return false;
        }
    }

    private Duration backoffFor(OutboxEntry entry) {
        long doublings = Math.min(entry.attempts(), 20);
        Duration backoff = properties.outbox().pollInterval().multipliedBy(1L << doublings);
        return backoff.compareTo(MAX_BACKOFF) > 0 ? MAX_BACKOFF : backoff;
    }
}
