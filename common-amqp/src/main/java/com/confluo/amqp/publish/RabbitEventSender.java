package com.confluo.amqp.publish;

import com.confluo.amqp.event.EventEnvelopeMessageConverter;
import com.confluo.amqp.outbox.OutboxEntry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;

import java.nio.charset.StandardCharsets;

/**
 * Gets one outbox entry to the broker.
 *
 * <p>The body is the stored bytes, unchanged: re-serializing at publish time would make the message
 * a consumer receives depend on code deployed after the event happened. The properties are rebuilt
 * by the converter from the entry's own columns, so a message published from the outbox is identical
 * to one the converter would have produced directly.</p>
 *
 * <p>Whether the broker actually took it is {@link ConfirmedPublisher}'s business, and the reason
 * this does not simply call {@code send}: the drainer marks the entry published on the strength of
 * this returning.</p>
 */
@Slf4j
public class RabbitEventSender {

    private final ConfirmedPublisher publisher;
    private final EventEnvelopeMessageConverter converter;
    private final String source;

    /**
     * @param publisher what publishes and waits to be told
     * @param converter where the wire format's headers are defined
     * @param source this service's name, which goes in the source header
     */
    public RabbitEventSender(
            ConfirmedPublisher publisher, EventEnvelopeMessageConverter converter, String source) {
        this.publisher = publisher;
        this.converter = converter;
        this.source = source;
    }

    /**
     * Publishes an entry and waits for the broker to confirm it.
     *
     * @param entry the entry to publish
     * @throws EventPublishException if the broker did not take it, sent it back, or said nothing
     */
    public void send(OutboxEntry entry) {
        Message message = new Message(
                entry.payload().getBytes(StandardCharsets.UTF_8),
                converter.properties(entry.id(), entry.eventType(), entry.eventVersion(), source,
                        entry.createdAt(), null));

        publisher.publish(entry.exchange(), entry.routingKey(), message, entry.id().toString());

        log.debug("Published event {} to '{}' with key '{}'",
                entry.id(), entry.exchange(), entry.routingKey());
    }
}
