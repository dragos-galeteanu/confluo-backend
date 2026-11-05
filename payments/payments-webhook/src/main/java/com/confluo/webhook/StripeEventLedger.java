package com.confluo.webhook;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stripe.model.Event;
import com.stripe.model.EventDataObjectDeserializer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;


/**
 * Writes the ledger row that makes an event impossible to apply twice.
 *
 * <p>Separate from {@link StripeWebhookService} for one specific reason: a duplicate surfaces as a
 * {@code DataIntegrityViolationException} at commit, and the caller that catches it must be outside
 * this transaction. Catching it within the same transactional method would leave a rollback-only
 * transaction that fails anyway at commit, which is the usual way this pattern is got wrong.</p>
 *
 * <p>The row is written in {@link StripeEventStatus#RECEIVED} and nothing is applied here. That
 * separation is the design: the endpoint's job is to get the event durably recorded and answer
 * Stripe quickly, and applying it is a second transaction that may fail and be retried without
 * ever putting the event at risk of being lost.</p>
 */
@RequiredArgsConstructor
@Slf4j
public class StripeEventLedger {

    private final StripeEventRepository repository;
    private final ObjectMapper objectMapper;

    /**
     * The ledger row for an event, if it has been seen.
     * @param stripeEventId Stripe's {@code evt_} identifier
     * @return the row, or empty
     */
    @Transactional(readOnly = true)
    public Optional<StripeEvent> find(String stripeEventId) {
        return repository.findByStripeEventId(stripeEventId);
    }

    /**
     * Records an event as received, and nothing more.
     *
     * @param event the verified event
     * @return the row written
     * @throws org.springframework.dao.DataIntegrityViolationException if the event is already in
     *         the ledger. Not an error: it is how two instances handling the same redelivery at the
     *         same instant are resolved, and the caller treats it as
     *         {@link StripeEventIngestion#DUPLICATE}
     */
    @Transactional
    public StripeEvent record(Event event) {
        StripeEvent row = new StripeEvent();
        row.setStripeEventId(event.getId());
        row.setEventType(event.getType());
        row.setApiVersion(event.getApiVersion());
        // Boolean on Stripe's side, and absent on a malformed event. Treated as test mode when
        // missing, because the alternative is recording something as live that may not be.
        row.setLivemode(Boolean.TRUE.equals(event.getLivemode()));
        // Null for the platform's own events, set for a connected account's.
        row.setStripeAccountId(event.getAccount());
        row.setObjectId(objectIdOf(event));
        row.setEventCreatedAt(event.getCreated() == null ? null : Instant.ofEpochSecond(event.getCreated()));
        row.setStatus(StripeEventStatus.RECEIVED);

        return repository.save(row);
    }

    /**
     * The id of the object an event is about, read out of the raw JSON.
     *
     * <p>Out of the JSON rather than out of a deserialised {@code StripeObject}, because
     * {@link EventDataObjectDeserializer#getObject()} returns empty whenever the event's API
     * version does not match the library's - which is precisely the case where knowing what the
     * event was about matters most. An id is a string in every Stripe object that has one, so
     * reading it needs none of that machinery.</p>
     *
     * @param event the event
     * @return the object's id, or null if the event's data object has none
     */
    private String objectIdOf(Event event) {
        EventDataObjectDeserializer deserializer = event.getDataObjectDeserializer();
        if (deserializer == null) {
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(deserializer.getRawJson());
            JsonNode id = node.get("id");
            return id == null || id.isNull() ? null : id.asText();
        } catch (Exception e) {
            // Worth a warning and not worth failing for: the event is still recorded, and the only
            // thing lost is being able to find this row from the object it was about.
            log.warn("Could not read the object id out of Stripe event {} [type={}]: {}",
                    event.getId(), event.getType(), e.getMessage());
            return null;
        }
    }
}
