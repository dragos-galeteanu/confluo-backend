package com.confluo.amqp.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

import java.util.function.Supplier;

/**
 * The handful of numbers worth watching about messaging.
 *
 * <p>Spring AMQP already publishes the broker-level ones - connections, channels, consumer counts.
 * What it cannot know is whether events are actually getting out and whether anything is giving up,
 * which is what these add. Two of them are worth alerting on: {@code confluo.outbox.pending}, which
 * should hover near zero and stay there, and {@code confluo.consumer.parked}, which should never
 * move at all.</p>
 */
public class AmqpMetrics {

    /** Events written to the outbox and not yet published. Should sit near zero. */
    public static final String OUTBOX_PENDING = "confluo.outbox.pending";

    /** Events the broker has confirmed. */
    public static final String OUTBOX_PUBLISHED = "confluo.outbox.published";

    /** Publish attempts the broker refused, returned or did not answer. */
    public static final String OUTBOX_FAILED = "confluo.outbox.failed";

    /** Messages sent to a retry tier after a handler failed. */
    public static final String CONSUMER_RETRIED = "confluo.consumer.retried";

    /** Messages that ran out of attempts, or could not be read at all. Should never move. */
    public static final String CONSUMER_PARKED = "confluo.consumer.parked";

    private final MeterRegistry registry;

    /**
     * @param registry where the meters go
     */
    public AmqpMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    /**
     * Watches how far behind the outbox is.
     *
     * <p>A gauge rather than a counter, and read on demand rather than cached, so the value is the
     * database's own answer at the moment it is asked for. It costs one indexed count per scrape.</p>
     *
     * @param pending how to find out how many are waiting
     */
    public void watchPending(Supplier<Number> pending) {
        Gauge.builder(OUTBOX_PENDING, pending)
                .description("Events written to the outbox and not yet published")
                .register(registry);
    }

    /**
     * Counts an event the broker confirmed.
     */
    public void published() {
        registry.counter(OUTBOX_PUBLISHED).increment();
    }

    /**
     * Counts a publish attempt that did not work.
     */
    public void failed() {
        registry.counter(OUTBOX_FAILED).increment();
    }

    /**
     * Counts a message sent to a retry tier.
     *
     * @param queue the queue it came from, so a single misbehaving handler is identifiable
     * @param attempt which attempt had just failed
     */
    public void retried(String queue, int attempt) {
        registry.counter(CONSUMER_RETRIED, "queue", queue, "attempt", Integer.toString(attempt))
                .increment();
    }

    /**
     * Counts a message that will not be tried again.
     *
     * @param queue the queue it came from
     */
    public void parked(String queue) {
        registry.counter(CONSUMER_PARKED, "queue", queue).increment();
    }
}
