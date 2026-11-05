package com.confluo.amqp.publish;

import com.confluo.amqp.event.EventDescriptor;
import com.confluo.amqp.event.EventEnvelope;
import com.confluo.amqp.event.EventEnvelopeMessageConverter;
import com.confluo.amqp.event.EventTypeRegistry;
import com.confluo.amqp.outbox.OutboxEntry;
import com.confluo.amqp.outbox.OutboxRepository;
import com.confluo.amqp.topology.AmqpNames;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Publishes by writing to the outbox, never by talking to the broker.
 *
 * <p>This is the whole of the transactional outbox on the producing side. The envelope is built and
 * serialized here, in the caller's transaction, and that is the last anything synchronous happens:
 * the row either commits with the business change or disappears with it. Sending is the drainer's
 * job, after the commit.</p>
 *
 * <p>The body is produced by the same converter a direct publish would use, so an event's bytes do
 * not depend on which path they took - and the conversion doubles as validation, rejecting an
 * envelope whose payload does not match what it claims to be.</p>
 */
@Slf4j
public class OutboxEventPublisher implements EventPublisher {

    /** Where Micrometer's Brave bridge puts the current trace, once tracing is wired up. */
    private static final String TRACE_ID_KEY = "traceId";

    /** Set by a service that threads a correlation id through its own work; usually absent. */
    private static final String CORRELATION_ID_KEY = "correlationId";

    private final EventTypeRegistry registry;
    private final EventEnvelopeMessageConverter converter;
    private final OutboxRepository repository;
    private final AmqpNames names;
    private final ApplicationEventPublisher applicationEvents;

    /**
     * @param registry the contracts a payload is described by
     * @param converter what serializes the envelope
     * @param repository the outbox
     * @param names this service's naming scheme, which supplies the exchange and the source
     * @param applicationEvents how the drainer is told there is something to send
     */
    public OutboxEventPublisher(
            EventTypeRegistry registry,
            EventEnvelopeMessageConverter converter,
            OutboxRepository repository,
            AmqpNames names,
            ApplicationEventPublisher applicationEvents) {
        this.registry = registry;
        this.converter = converter;
        this.repository = repository;
        this.names = names;
        this.applicationEvents = applicationEvents;
    }

    @Override
    public <T> UUID publish(UUID aggregateId, T payload) {
        if (payload == null) {
            throw new IllegalArgumentException("There is no event to publish");
        }
        EventDescriptor descriptor = registry.describe(payload.getClass());

        EventEnvelope<T> envelope = EventEnvelope.create(
                descriptor, aggregateId, payload, names.serviceName(),
                MDC.get(TRACE_ID_KEY), MDC.get(CORRELATION_ID_KEY), currentActor());

        byte[] body = converter.toMessage(envelope, new MessageProperties()).getBody();

        OutboxEntry entry = OutboxEntry.pending(
                envelope.eventId(),
                names.eventsExchange(),
                descriptor.type(),
                descriptor.type(),
                descriptor.version(),
                new String(body, StandardCharsets.UTF_8),
                envelope.occurredAt());

        repository.save(entry);
        applicationEvents.publishEvent(new OutboxEntryWritten(entry.id()));

        log.debug("Event {} written to the outbox [{}, aggregate={}]",
                entry.id(), descriptor.label(), aggregateId);
        return envelope.eventId();
    }

    /**
     * Who caused this, as far as the current request knows.
     *
     * <p>Nullable on purpose, and read loosely rather than through
     * {@code SecurityUtils.extractUserId}, which refuses a request with no usable user. An event
     * may legitimately have no actor: a scheduled job, a migration, or anything the system did to
     * itself. Refusing to publish in that case would be the wrong trade entirely.</p>
     *
     * <p>The platform user id is preferred over the token's subject because that is the id the rest
     * of the platform uses; the subject is the fallback when a token has no platform user yet.</p>
     *
     * @return the actor's id, or null if there is nobody
     */
    private static String currentActor() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null
                || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken) {
            return null;
        }
        if (authentication.getPrincipal() instanceof Jwt jwt) {
            String userId = jwt.getClaimAsString("userId");
            return userId != null && !userId.isBlank() ? userId : jwt.getSubject();
        }
        return authentication.getName();
    }
}
