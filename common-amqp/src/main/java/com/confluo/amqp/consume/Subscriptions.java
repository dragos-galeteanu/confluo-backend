package com.confluo.amqp.consume;

import java.util.List;

/**
 * Every handler this service has, resolved once.
 *
 * <p>A type of its own rather than a {@code List<Subscription>} bean, because injecting a list asks
 * Spring for every bean of the element type rather than for a bean that is the list - so the
 * topology and the containers would quietly receive nothing.</p>
 *
 * @param all the subscriptions
 */
public record Subscriptions(List<Subscription> all) {

    /**
     * @param all the subscriptions
     */
    public Subscriptions(List<Subscription> all) {
        this.all = List.copyOf(all);
    }

    /**
     * Whether this service consumes anything.
     * @return true if there are no handlers
     */
    public boolean isEmpty() {
        return all.isEmpty();
    }
}
