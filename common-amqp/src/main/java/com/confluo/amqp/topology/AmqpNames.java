package com.confluo.amqp.topology;

import java.time.Duration;
import java.util.List;

/**
 * Every exchange and queue name this service uses, derived from one string.
 *
 * <p>No name is spelled twice anywhere else. A producer's exchange, a consumer's queue, the retry
 * tiers and the parking lot all come from here, which is what makes it possible to reason about the
 * topology without reading the broker: given a service name, every object it owns is predictable,
 * and a consumer can work out a producer's exchange without being told.</p>
 *
 * <p>The consumer queue name carries both services and the event, {@code
 * notifications.platform.currency.disabled}. That is deliberately verbose: a queue in the
 * management UI should say who listens, who publishes and to what, because that is exactly what a
 * person needs to know when they find it backed up.</p>
 */
public class AmqpNames {

    /** Characters that would make a derived routing key match something it should not. */
    private static final List<String> FORBIDDEN_IN_NAMES = List.of("*", "#", " ", "\t");

    private final String serviceName;

    /**
     * @param serviceName this service's name, as {@code AmqpProperties} resolves it
     */
    public AmqpNames(String serviceName) {
        this.serviceName = require(serviceName, "service name");
    }

    /**
     * The name everything here is derived from.
     * @return this service's name
     */
    public String serviceName() {
        return serviceName;
    }

    /**
     * The topic exchange this service publishes its own events to.
     * @return {@code <service>.events}
     */
    public String eventsExchange() {
        return eventsExchangeOf(serviceName);
    }

    /**
     * The topic exchange another service publishes to.
     *
     * <p>A consumer needs this to bind, and declares it as well as its own queue - so a consumer
     * can be deployed before the producer exists without its binding failing.</p>
     *
     * @param producer the publishing service's name
     * @return {@code <producer>.events}
     */
    public String eventsExchangeOf(String producer) {
        return require(producer, "producer name") + ".events";
    }

    /**
     * The direct exchange a failed message is republished to, to wait in a tier queue.
     * @return {@code <service>.retry}
     */
    public String retryExchange() {
        return serviceName + ".retry";
    }

    /**
     * The direct exchange a tier queue dead-letters to when its messages expire.
     *
     * <p>This exists because a tier queue cannot dead-letter straight back onto the main queue: it
     * would have to do so through the producer's exchange, whose routing keys belong to the
     * producer. A queue of this service's own, keyed by queue name, keeps the return path under
     * this service's control.</p>
     *
     * @return {@code <service>.requeue}
     */
    public String requeueExchange() {
        return serviceName + ".requeue";
    }

    /**
     * The direct exchange a message is republished to once it has run out of attempts.
     * @return {@code <service>.parking-lot}
     */
    public String parkingLotExchange() {
        return serviceName + ".parking-lot";
    }

    /**
     * The queue this service consumes one subscription from.
     *
     * @param producer the publishing service's name
     * @param subscription the event type, e.g. {@code currency.disabled}, or the handler's own
     *                     suffix when it subscribes to a pattern - a pattern itself would make a
     *                     queue name nobody can read
     * @return {@code <service>.<producer>.<subscription>}
     */
    public String queue(String producer, String subscription) {
        return "%s.%s.%s".formatted(
                serviceName, require(producer, "producer name"), require(subscription, "subscription"));
    }

    /**
     * The tier queue a message waits in before its next attempt.
     * @param queue the main queue's name
     * @param delay how long the tier holds a message
     * @return {@code <queue>.retry.<delay>}
     */
    public String retryQueue(String queue, Duration delay) {
        return "%s.retry.%s".formatted(require(queue, "queue name"), delayLabel(delay));
    }

    /**
     * The queue a message ends up in when it has failed every attempt.
     * @param queue the main queue's name
     * @return {@code <queue>.parking-lot}
     */
    public String parkingLotQueue(String queue) {
        return require(queue, "queue name") + ".parking-lot";
    }

    /**
     * A delay as it appears in a queue name.
     *
     * <p>The largest whole unit, so the plan's {@code 5s / 30s / 5m} read back as {@code 5s},
     * {@code 30s} and {@code 5m} rather than as milliseconds. A queue name is read by people.</p>
     *
     * @param delay the delay
     * @return a compact label, e.g. {@code 30s}
     */
    public static String delayLabel(Duration delay) {
        if (delay == null || delay.isNegative() || delay.isZero()) {
            throw new IllegalArgumentException("A retry delay has to be positive, not " + delay);
        }
        long millis = delay.toMillis();
        if (millis % 3_600_000 == 0) {
            return delay.toHours() + "h";
        }
        if (millis % 60_000 == 0) {
            return delay.toMinutes() + "m";
        }
        if (millis % 1_000 == 0) {
            return delay.toSeconds() + "s";
        }
        return millis + "ms";
    }

    private static String require(String value, String what) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("A blank %s cannot name an AMQP object".formatted(what));
        }
        for (String forbidden : FORBIDDEN_IN_NAMES) {
            if (value.contains(forbidden)) {
                throw new IllegalArgumentException(
                        "The %s '%s' contains '%s', which has a meaning in a routing key"
                                .formatted(what, value, forbidden.isBlank() ? "whitespace" : forbidden));
            }
        }
        return value;
    }
}
