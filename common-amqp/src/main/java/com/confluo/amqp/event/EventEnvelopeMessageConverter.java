package com.confluo.amqp.event;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.support.converter.MessageConversionException;
import org.springframework.amqp.support.converter.MessageConverter;

import java.io.IOException;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;
import java.util.UUID;

/**
 * Turns an {@link EventEnvelope} into a message and back.
 *
 * <p>Spring's own {@code Jackson2JsonMessageConverter} cannot do this, which is why there is a
 * converter here at all: the envelope is generic, so decoding needs the payload class, and the
 * only thing that knows it is {@link EventTypeRegistry} keyed by what the message says it is. The
 * type and version are written to headers on the way out and read from them on the way back, so
 * resolving the class does not mean parsing the body twice. A message that arrives without those
 * headers - hand-published, or replayed by a tool - is still decodable, by reading the two fields
 * out of the JSON.</p>
 *
 * <p>The {@link ObjectMapper} is this converter's own rather than the application's. A service is
 * free to configure {@code spring.jackson.*} for its HTTP API however it likes; none of that may
 * reach the wire format, because the consumer deserializing the message is a different service
 * with different settings.</p>
 */
public class EventEnvelopeMessageConverter implements MessageConverter {

    /** The event's wire name, so the payload class can be resolved without parsing the body. */
    public static final String EVENT_TYPE_HEADER = "x-confluo-event-type";

    /** The contract version, which together with the type identifies one contract. */
    public static final String EVENT_VERSION_HEADER = "x-confluo-event-version";

    /** The event id, so a message can be tied to a log line or a ledger row without decoding. */
    public static final String EVENT_ID_HEADER = "x-confluo-event-id";

    /** The publishing service, which is useful when staring at a parking lot in the UI. */
    public static final String SOURCE_HEADER = "x-confluo-source";

    private final EventTypeRegistry registry;
    private final ObjectMapper objectMapper;

    /**
     * @param registry the contracts this service knows
     */
    public EventEnvelopeMessageConverter(EventTypeRegistry registry) {
        this(registry, newObjectMapper());
    }

    /**
     * @param registry the contracts this service knows
     * @param objectMapper the mapper to use, for a test that needs to vary it
     */
    public EventEnvelopeMessageConverter(EventTypeRegistry registry, ObjectMapper objectMapper) {
        this.registry = registry;
        this.objectMapper = objectMapper;
    }

    /**
     * The wire format, which is deliberately boring.
     *
     * <p>Timestamps are ISO-8601 text rather than epoch numbers: a message is read by a person in
     * the management UI at exactly the moment things have gone wrong, and {@code
     * "2026-10-26T09:15:00Z"} is worth more then than {@code 1792005300.000000000}.</p>
     *
     * <p>Unknown properties are ignored, which is the whole of the tolerant-reader promise - it is
     * what lets a producer add a field without redeploying a single consumer.</p>
     *
     * @return a mapper configured for the event wire format
     */
    public static ObjectMapper newObjectMapper() {
        return JsonMapper.builder()
                .addModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .build();
    }

    @Override
    public Message toMessage(Object object, MessageProperties properties)
            throws MessageConversionException {
        if (!(object instanceof EventEnvelope<?> envelope)) {
            throw new MessageConversionException(
                    "Only an EventEnvelope can be published, not %s"
                            .formatted(object == null ? "null" : object.getClass().getName()));
        }

        EventDescriptor descriptor = describe(envelope);
        if (!descriptor.type().equals(envelope.eventType()) || descriptor.version() != envelope.version()) {
            throw new MessageConversionException(
                    "Envelope %s says it is %s@v%d but its payload %s is %s"
                            .formatted(envelope.eventId(), envelope.eventType(), envelope.version(),
                                    descriptor.payloadType().getSimpleName(), descriptor.label()));
        }

        stamp(properties, envelope.eventId(), descriptor.type(), descriptor.version(),
                envelope.source(), envelope.occurredAt(), envelope.correlationId());

        try {
            return new Message(objectMapper.writeValueAsBytes(envelope), properties);
        } catch (IOException e) {
            throw new MessageConversionException(
                    "Could not serialize event %s (%s)".formatted(envelope.eventId(), descriptor.label()), e);
        }
    }

    /**
     * The message properties an event is published with, built from what an outbox row holds.
     *
     * <p>Here rather than in the drainer so there is one definition of the wire format. The drainer
     * publishes a stored body with properties it did not keep, and those properties have to match
     * what {@link #toMessage} would have produced, or a message's headers would depend on which
     * path it took.</p>
     *
     * @param eventId the event's id
     * @param eventType the wire name
     * @param version the contract version
     * @param source the publishing service
     * @param occurredAt when the event happened
     * @param correlationId the wider conversation, may be null
     * @return the properties to publish with
     */
    public MessageProperties properties(
            UUID eventId, String eventType, int version, String source, Instant occurredAt,
            String correlationId) {
        MessageProperties properties = new MessageProperties();
        stamp(properties, eventId, eventType, version, source, occurredAt, correlationId);
        return properties;
    }

    private static void stamp(
            MessageProperties properties, UUID eventId, String eventType, int version, String source,
            Instant occurredAt, String correlationId) {
        properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        properties.setContentEncoding("UTF-8");
        properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        properties.setMessageId(eventId.toString());
        properties.setTimestamp(Date.from(occurredAt));
        properties.setHeader(EVENT_TYPE_HEADER, eventType);
        properties.setHeader(EVENT_VERSION_HEADER, version);
        properties.setHeader(EVENT_ID_HEADER, eventId.toString());
        properties.setHeader(SOURCE_HEADER, source);
        if (correlationId != null) {
            properties.setCorrelationId(correlationId);
        }
    }

    @Override
    public Object fromMessage(Message message) throws MessageConversionException {
        byte[] body = message.getBody();
        String eventType = header(message, EVENT_TYPE_HEADER)
                .orElseGet(() -> field(body, "eventType"));
        int version = header(message, EVENT_VERSION_HEADER)
                .map(Integer::parseInt)
                .orElseGet(() -> {
                    String value = field(body, "version");
                    return value == null ? 0 : Integer.parseInt(value);
                });

        EventDescriptor descriptor = registry.resolve(eventType, version)
                .orElseThrow(() -> new MessageConversionException(
                        "Unknown event %s@v%d; this service knows %s"
                                .formatted(eventType, version, registry.descriptors().stream()
                                        .map(EventDescriptor::label)
                                        .toList())));

        JavaType envelopeType = objectMapper.getTypeFactory()
                .constructParametricType(EventEnvelope.class, descriptor.payloadType());
        try {
            return objectMapper.readValue(body, envelopeType);
        } catch (IOException e) {
            throw new MessageConversionException(
                    "Could not deserialize event %s".formatted(descriptor.label()), e);
        }
    }

    private EventDescriptor describe(EventEnvelope<?> envelope) {
        try {
            return registry.describe(envelope.payload().getClass());
        } catch (IllegalArgumentException e) {
            throw new MessageConversionException(e.getMessage(), e);
        }
    }

    private static Optional<String> header(Message message, String name) {
        Object value = message.getMessageProperties().getHeader(name);
        return value == null ? Optional.empty() : Optional.of(value.toString());
    }

    /**
     * Reads one top-level field out of the body, for a message whose headers were lost.
     *
     * @param body the message body
     * @param name the field to read
     * @return the field's text, or null if it is absent
     */
    private String field(byte[] body, String name) {
        try {
            JsonNode node = objectMapper.readTree(body).get(name);
            return node == null || node.isNull() ? null : node.asText();
        } catch (IOException e) {
            throw new MessageConversionException(
                    "Message carries no %s header and its body is not readable JSON"
                            .formatted(EVENT_TYPE_HEADER), e);
        }
    }
}
