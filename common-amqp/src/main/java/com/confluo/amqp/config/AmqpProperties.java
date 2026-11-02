package com.confluo.amqp.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;
import java.util.List;
import java.util.Locale;

/**
 * Everything about this service's messaging that is not a broker connection detail.
 *
 * <p>The connection itself - host, credentials, publisher confirms, acknowledge mode - stays under
 * Spring's own {@code spring.rabbitmq.*}, because reusing those keys is what keeps a future move
 * to a user and vhost per service a matter of configuration rather than code.</p>
 *
 * <p>Bound by constructor, so every value is final and a typo in a key name cannot leave a field
 * silently null: an unbindable property fails at startup instead.</p>
 *
 * @param serviceName the name every exchange, queue and envelope {@code source} is derived from,
 *                    defaulting to the application's own name - see
 *                    {@link #resolveServiceName(String)}
 * @param queueType whether declared queues are classic or quorum
 * @param confirmTimeout how long to wait for the broker to confirm one publish. Both halves of the
 *                       module publish - the drainer sends events, the error handler sends retries -
 *                       and both block on it, so it cannot be unbounded: a broker that accepts a
 *                       connection and then goes quiet would otherwise hold a consumer or a sweep
 *                       open indefinitely
 * @param outbox how the transactional outbox is drained
 * @param consumer how incoming messages are handled and retried
 * @param consumedMessage how long the idempotency ledger is kept
 */
@ConfigurationProperties("confluo.amqp")
public record AmqpProperties(
        String serviceName,
        @DefaultValue("classic") QueueType queueType,
        @DefaultValue("5s") Duration confirmTimeout,
        @DefaultValue Outbox outbox,
        @DefaultValue Consumer consumer,
        @DefaultValue ConsumedMessage consumedMessage) {

    /**
     * The service name, falling back to the application's own.
     *
     * <p>Both names are nearly always the same, so making {@code confluo.amqp.service-name}
     * mandatory would be a property every service had to remember for no benefit. It stays
     * configurable because the name is baked into exchange and queue names: a service that is
     * renamed can keep publishing to the exchange its consumers are already bound to.</p>
     *
     * <p>Neither being set is a startup failure rather than a default, because the alternative is
     * a service quietly publishing to an exchange named after nothing.</p>
     *
     * @param applicationName the value of {@code spring.application.name}, which may be null
     * @return the name to derive AMQP object names from
     * @throws IllegalStateException if neither name is set
     */
    public String resolveServiceName(String applicationName) {
        if (serviceName != null && !serviceName.isBlank()) {
            return serviceName;
        }
        if (applicationName != null && !applicationName.isBlank()) {
            return applicationName;
        }
        throw new IllegalStateException(
                "Messaging needs a service name: set confluo.amqp.service-name or spring.application.name");
    }

    /**
     * The kind of queue this service declares.
     *
     * <p>Classic locally, where a single node is the whole cluster and the extra guarantees buy
     * nothing, and quorum where the service is deployed.</p>
     */
    public enum QueueType {

        /** The original queue type: simplest semantics, no quorum caveats. */
        CLASSIC,

        /** Raft-replicated and the modern durable default, with the caveats noted below. */
        QUORUM;

        /**
         * The value for the {@code x-queue-type} declaration argument.
         *
         * <p>A queue's type cannot be changed after it is declared, so switching an environment
         * from one to the other means deleting and recreating the queue while it is drained.</p>
         *
         * @return the argument value the broker expects
         */
        public String argument() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /**
     * How the outbox is drained.
     *
     * @param enabled whether this service drains the outbox at all; a service that only consumes
     *                has nothing to drain
     * @param batchSize how many pending entries one sweep claims
     * @param pollInterval how often the sweeper runs, which is the worst-case publish latency when
     *                     the immediate after-commit attempt fails
     * @param maxAttempts how many times an entry is retried before it is left alone and logged
     * @param staleAfter how old the oldest unpublished entry has to be before the outbox is
     *                   reported as degraded. Generously longer than the poll interval, because a
     *                   drain in progress is not a problem - what this catches is an outbox that
     *                   has stopped moving
     */
    public record Outbox(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("100") int batchSize,
            @DefaultValue("1s") Duration pollInterval,
            @DefaultValue("10") int maxAttempts,
            @DefaultValue("1m") Duration staleAfter) {
    }

    /**
     * How incoming messages are handled.
     *
     * <p>{@code retryDelays} holds one delay per retry, so the number of delays is one fewer than
     * {@code maxAttempts}: with four attempts, a message that keeps failing waits 5s, then 30s,
     * then 5m, and the fourth failure sends it to the parking lot. The delay is held by the broker
     * in a queue whose messages expire back onto this one, so no consumer thread waits and a
     * redeploy mid-backoff loses nothing.</p>
     *
     * <p>Note that concurrency above one means messages are not delivered in order, which is why a
     * handler is expected to tolerate an event arriving after a later one - the usual move being
     * to ignore an event whose {@code occurredAt} precedes the state the handler already holds.</p>
     *
     * @param enabled whether this service consumes at all; a publisher-only service that has no
     *                handlers can leave it on, but switching it off keeps a service that neither
     *                publishes nor consumes from needing a database
     * @param prefetch how many unacknowledged messages one consumer may hold
     * @param concurrency how many consumers a queue starts with
     * @param maxConcurrency how many consumers a queue may grow to
     * @param maxAttempts how many times a message is handled before it is parked
     * @param retryDelays the wait before each retry, shortest first
     */
    public record Consumer(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("20") int prefetch,
            @DefaultValue("2") int concurrency,
            @DefaultValue("8") int maxConcurrency,
            @DefaultValue("4") int maxAttempts,
            @DefaultValue({"5s", "30s", "5m"}) List<Duration> retryDelays) {
    }

    /**
     * How long the record of what has already been consumed is kept.
     *
     * <p>The window has to outlive the longest redelivery, which the retry tiers put at minutes.
     * It is set far longer than that so a message replayed out of a parking lot days later still
     * finds its marker and is recognised as a duplicate rather than handled a second time.</p>
     *
     * @param retention how long a consumed-message row is kept
     * @param purgeCron when the purge runs
     */
    public record ConsumedMessage(
            @DefaultValue("30d") Duration retention,
            @DefaultValue("0 15 3 * * *") String purgeCron) {
    }
}
