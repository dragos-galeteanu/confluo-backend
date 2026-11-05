package com.confluo.webhook;

import com.confluo.webhook.payload.StripeEventPayload;
import com.confluo.webhook.payload.StripeEventPayloadRepository;
import com.confluo.stripe.StripeProperties;
import com.stripe.exception.SignatureVerificationException;
import com.stripe.model.Event;
import com.stripe.net.Webhook;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * Accepts a webhook from Stripe: verifies it, records it, archives it.
 *
 * <p><strong>Nothing is applied here.</strong> This service's whole job is to get a verified event
 * durably recorded and let the endpoint answer Stripe quickly; the handlers that act on an event
 * are a separate pass over the ledger. That is not tidiness - it is what makes a bug in a handler
 * cost a retry rather than a lost event, and what keeps a slow handler from making Stripe time out
 * and redeliver.</p>
 *
 * <p>Deliberately not {@code @Transactional}. The duplicate check relies on catching a constraint
 * violation thrown at commit, which can only be caught outside the transaction that caused it -
 * see {@link StripeEventLedger}.</p>
 *
 * <h2>Order of operations</h2>
 *
 * <p>Verify, then record, then archive, and in that order for reasons that each matter:</p>
 *
 * <ol>
 *   <li>an unverified request is not an event and gets no row;</li>
 *   <li>the ledger row in PostgreSQL is the guarantee, so it commits first;</li>
 *   <li>the raw body in MongoDB is evidence beside it, and is best-effort: MongoDB being
 *       unreachable costs the archive, not the event. Failing the request instead would have
 *       Stripe redeliver an event that is already recorded, and the redelivery would be a
 *       duplicate - so the event would be stuck, unapplied, with the endpoint reporting failure
 *       forever.</li>
 * </ol>
 */
@RequiredArgsConstructor
@Slf4j
public class StripeWebhookService {

    private final StripeEventLedger ledger;
    private final StripeEventPayloadRepository payloads;
    private final StripeProperties properties;

    /**
     * Takes one webhook request.
     *
     * @param source which endpoint it arrived at, and so which secret verifies it
     * @param payload the raw request body, exactly as it arrived - the signature is computed over
     *                these bytes, so anything that has been parsed and re-rendered will not verify
     * @param signatureHeader the {@code Stripe-Signature} header
     * @return whether a row was written, or the event was already known
     * @throws SignatureVerificationException if the signature does not match, the body has been
     *         altered, or the timestamp is outside Stripe's tolerance - which is also the replay
     *         protection, and the caller answers all of it with a 400
     */
    public StripeEventIngestion ingest(StripeWebhookSource source, String payload, String signatureHeader)
            throws SignatureVerificationException {

        Event event = Webhook.constructEvent(payload, signatureHeader, secretFor(source));

        // The cheap look before the insert. The unique constraint is what actually decides, so this
        // only saves a caught exception in the ordinary retry case.
        if (ledger.find(event.getId()).isPresent()) {
            archiveIfMissing(event, payload, signatureHeader);
            log.debug("Stripe event already in the ledger [id={}, type={}, source={}]",
                    event.getId(), event.getType(), source);
            return StripeEventIngestion.DUPLICATE;
        }

        StripeEvent row;
        try {
            row = ledger.record(event);
        } catch (DataIntegrityViolationException e) {
            // Two deliveries of one event arrived at the same instant and this one lost. The
            // correct outcome, and the reason the constraint exists rather than the check above
            // being trusted on its own.
            log.debug("Stripe event was recorded concurrently [id={}, type={}]",
                    event.getId(), event.getType());
            return StripeEventIngestion.DUPLICATE;
        }

        archive(event, payload, signatureHeader);

        // The payload is never logged: it carries customer details, and so does the signature.
        log.info("Stripe event recorded [id={}, type={}, source={}, livemode={}, account={}, objectId={}]",
                row.getStripeEventId(), row.getEventType(), source, row.isLivemode(),
                row.getStripeAccountId(), row.getObjectId());
        return StripeEventIngestion.RECORDED;
    }

    /**
     * Stores the raw body beside the ledger row.
     *
     * <p>Swallows its own failure on purpose - see the class javadoc. The warning is the point:
     * a ledger row with no archived body is worth knowing about, because it is the one case where
     * the question "what did Stripe actually say?" has no answer.</p>
     *
     * @param event the verified event
     * @param payload the raw body
     * @param signatureHeader the header it was verified with
     */
    private void archive(Event event, String payload, String signatureHeader) {
        try {
            StripeEventPayload archived = new StripeEventPayload();
            archived.setStripeEventId(event.getId());
            archived.setEventType(event.getType());
            archived.setSignatureHeader(signatureHeader);
            archived.setPayload(payload);
            payloads.save(archived);
        } catch (RuntimeException e) {
            log.warn("Stripe event {} is in the ledger but its body could not be archived: {}",
                    event.getId(), e.getMessage());
        }
    }

    /**
     * Archives the body of an event already in the ledger, if it has none.
     *
     * <p>Which repairs the one case {@link #archive} leaves behind: a first delivery that recorded
     * the row and then could not reach MongoDB. Stripe's retry comes back as a duplicate, and
     * there is no other moment at which that body is ever offered again.</p>
     *
     * @param event the verified event
     * @param payload the raw body
     * @param signatureHeader the header it was verified with
     */
    private void archiveIfMissing(Event event, String payload, String signatureHeader) {
        try {
            if (payloads.existsByStripeEventId(event.getId())) {
                return;
            }
        } catch (RuntimeException e) {
            log.warn("Could not check whether Stripe event {} is archived: {}", event.getId(), e.getMessage());
            return;
        }
        log.info("Archiving the body of Stripe event {} from a redelivery", event.getId());
        archive(event, payload, signatureHeader);
    }

    /**
     * The signing secret for an endpoint.
     * @param source which endpoint the request arrived at
     * @return its secret
     */
    private String secretFor(StripeWebhookSource source) {
        return switch (source) {
            case PLATFORM -> properties.webhookSecret();
            case CONNECT -> properties.connectWebhookSecret();
        };
    }
}
