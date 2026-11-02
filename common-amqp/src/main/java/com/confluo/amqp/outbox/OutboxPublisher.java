package com.confluo.amqp.outbox;

/**
 * Drains the outbox to RabbitMQ.
 *
 * <p>Why it matters: publishing inside a database transaction can succeed while the transaction
 * rolls back (phantom event), and publishing after commit can be lost if the process dies in
 * between (missing event). Writing the event to an outbox table in the same transaction and
 * publishing from there afterwards removes both cases. Combined with the consumed-message ledger on
 * the consumer side, that is what makes an operation safe to retry.</p>
 */
public interface OutboxPublisher {

    /**
     * Publishes a batch of pending entries.
     *
     * @param batchSize how many to take at most
     * @return how many were published
     */
    int publishPending(int batchSize);
}
