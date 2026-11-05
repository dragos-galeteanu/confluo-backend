package com.confluo.amqp.event;

import java.util.Locale;

/**
 * What a {@link DomainEvent} annotation says, read once and kept.
 *
 * <p>Built by {@link EventTypeRegistry}, which is also where the names are validated, so anything
 * holding a descriptor can trust it: the type has two segments, neither is blank, and neither
 * carries a wildcard.</p>
 *
 * @param payloadType the record the event is carried in
 * @param aggregate the thing the event happened to
 * @param event what happened to it
 * @param version the contract version
 */
public record EventDescriptor(Class<?> payloadType, String aggregate, String event, int version) {

    /**
     * The event's name on the wire, which is also its routing key.
     *
     * <p>The two are deliberately the same string. A consumer binds on the routing key and then
     * checks the envelope's {@code eventType}; keeping them identical means there is nothing to
     * keep in step.</p>
     *
     * @return {@code <aggregate>.<event>}, e.g. {@code currency.disabled}
     */
    public String type() {
        return aggregate + "." + event;
    }

    /**
     * A label for logs and queue-name suffixes, distinguishing versions of one type.
     * @return {@code <aggregate>.<event>@v<version>}
     */
    public String label() {
        return "%s@v%d".formatted(type(), version);
    }

    /**
     * The identity a message carries in its headers, used to find this descriptor again.
     * @return the type and version as one key
     */
    public EventTypeRegistry.Key key() {
        return new EventTypeRegistry.Key(type().toLowerCase(Locale.ROOT), version);
    }
}
