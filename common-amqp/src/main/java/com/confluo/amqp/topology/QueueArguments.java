package com.confluo.amqp.topology;

import com.confluo.amqp.config.AmqpProperties.QueueType;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The declaration arguments every queue in this module is declared with.
 *
 * <p>There is a helper for this rather than a map built at each call site because of one argument
 * in particular. A quorum queue's default dead-letter strategy is {@code at-most-once}, which
 * <em>can drop a message while dead-lettering</em> - and dead-lettering is not an edge case here,
 * it is how every retry tier returns a message to the main queue. Left at the default, the busiest
 * path in the topology would quietly lose messages. Setting {@code at-least-once} requires
 * {@code x-overflow: reject-publish}; the broker refuses the strategy without it.</p>
 *
 * <p>Both are set only for quorum queues. A classic queue has neither setting and declaring one on
 * it would be a declaration the broker has no use for.</p>
 */
public final class QueueArguments {

    /** Whether the queue is classic or quorum. Cannot be changed after declaration. */
    public static final String QUEUE_TYPE = "x-queue-type";

    /** Where a message goes when it is dead-lettered. */
    public static final String DEAD_LETTER_EXCHANGE = "x-dead-letter-exchange";

    /** The routing key a dead-lettered message is given, replacing its original. */
    public static final String DEAD_LETTER_ROUTING_KEY = "x-dead-letter-routing-key";

    /** Quorum only: whether dead-lettering may lose a message. */
    public static final String DEAD_LETTER_STRATEGY = "x-dead-letter-strategy";

    /** Quorum only: what happens when the queue is full, which at-least-once depends on. */
    public static final String OVERFLOW = "x-overflow";

    /** How long a message may sit in the queue before it expires and is dead-lettered. */
    public static final String MESSAGE_TTL = "x-message-ttl";

    /** The dead-letter strategy that does not lose messages. */
    public static final String AT_LEAST_ONCE = "at-least-once";

    /** The overflow behaviour at-least-once requires. */
    public static final String REJECT_PUBLISH = "reject-publish";

    private final Map<String, Object> arguments = new LinkedHashMap<>();

    private QueueArguments(QueueType queueType) {
        arguments.put(QUEUE_TYPE, queueType.argument());
        if (queueType == QueueType.QUORUM) {
            arguments.put(DEAD_LETTER_STRATEGY, AT_LEAST_ONCE);
            arguments.put(OVERFLOW, REJECT_PUBLISH);
        }
    }

    /**
     * Starts from the arguments every queue of this type needs.
     * @param queueType the configured queue type
     * @return a new builder
     */
    public static QueueArguments of(QueueType queueType) {
        return new QueueArguments(queueType);
    }

    /**
     * Sends what this queue rejects or expires to a known place with a known key.
     *
     * <p>The routing key is replaced rather than kept, because a dead-lettered message otherwise
     * carries the key it was published with - which belongs to the producer's exchange and means
     * nothing on this service's own.</p>
     *
     * @param exchange the exchange to dead-letter to
     * @param routingKey the key to dead-letter with
     * @return this builder
     */
    public QueueArguments deadLetteringTo(String exchange, String routingKey) {
        arguments.put(DEAD_LETTER_EXCHANGE, exchange);
        arguments.put(DEAD_LETTER_ROUTING_KEY, routingKey);
        return this;
    }

    /**
     * Makes every message in this queue expire after a fixed time, which is how a retry tier waits.
     *
     * <p>Queue-level rather than per-message: queue-level is what a quorum queue supports, so the
     * same topology works under either queue type.</p>
     *
     * @param ttl how long a message lives here
     * @return this builder
     */
    public QueueArguments expiringAfter(Duration ttl) {
        if (ttl == null || ttl.isNegative() || ttl.isZero()) {
            throw new IllegalArgumentException("A message TTL has to be positive, not " + ttl);
        }
        arguments.put(MESSAGE_TTL, ttl.toMillis());
        return this;
    }

    /**
     * @return the arguments to declare a queue with
     */
    public Map<String, Object> build() {
        return Map.copyOf(arguments);
    }
}
