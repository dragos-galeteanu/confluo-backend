package com.confluo.amqp.event;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a record as something a service publishes, and says what it is called on the wire.
 *
 * <p>This is the single place an event's name is written down. The publisher derives the routing
 * key from it, the envelope's {@code eventType} is it, and a consumer resolves the record class
 * back from it - so no routing key is ever passed by hand and no consumer matches on a string
 * literal that could drift from the record it belongs to.</p>
 *
 * <pre>{@code
 * @DomainEvent(aggregate = "currency", event = "disabled")
 * public record CurrencyDisabledEvent(UUID currencyId, Instant disabledAt) {}
 * }</pre>
 *
 * <p>That publishes to {@code platform.events} with routing key {@code currency.disabled}, and a
 * consumer binding {@code currency.*} receives it.</p>
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface DomainEvent {

    /**
     * The thing the event happened to, as the first segment of the routing key.
     *
     * <p>Must not contain a dot or an AMQP wildcard: the routing key has exactly two segments, and
     * a dot here would silently produce a third that no consumer's binding expects.</p>
     *
     * @return the aggregate name, e.g. {@code currency}
     */
    String aggregate();

    /**
     * What happened to it, as the second segment of the routing key.
     *
     * <p>Past tense, because an event is a statement about something that already happened.</p>
     *
     * @return the event name, e.g. {@code disabled}
     */
    String event();

    /**
     * The contract version.
     *
     * <p>Adding a field does not change this - consumers ignore what they do not know, so an
     * additive change needs no coordination. A breaking change bumps it, and the producer then
     * publishes both the old and the new event, on the same routing key, until every consumer has
     * moved. That is why a type and a version together identify a contract, and why two records
     * may share a routing key.</p>
     *
     * @return the version, starting at 1
     */
    int version() default 1;
}
