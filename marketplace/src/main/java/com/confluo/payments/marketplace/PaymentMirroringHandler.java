package com.confluo.payments.marketplace;

import com.confluo.amqp.event.payments.PaymentFailedEvent;
import com.confluo.amqp.event.payments.PaymentSucceededEvent;
import com.confluo.amqp.publish.EventPublisher;
import com.confluo.webhook.StripeEvent;
import com.confluo.webhook.StripeEventHandler;
import com.stripe.model.Event;
import com.stripe.model.StripeObject;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.Optional;
import java.util.UUID;


/**
 * What every handler that updates a mirrored payment needs, so none of them writes it again.
 *
 * <p>Three things, and each of them is a mistake waiting to happen if a handler is left to
 * remember it:</p>
 *
 * <ul>
 *   <li><strong>getting the Stripe object out of the event</strong>, which has a trap in it - see
 *       {@link #objectOf};</li>
 *   <li><strong>finding the payment</strong>, and deciding what it means when there is no row;</li>
 *   <li><strong>ignoring an event older than the row</strong>. Stripe guarantees no ordering, so a
 *       delayed {@code payment_intent.processing} can arrive after the
 *       {@code payment_intent.succeeded} for the same payment - and applying it would move a paid
 *       payment back to in-flight. {@link #isStale} is the guard, and it is why every mirrored
 *       entity carries {@code lastStripeEventAt}.</li>
 * </ul>
 */
@RequiredArgsConstructor
@Slf4j
public abstract class PaymentMirroringHandler implements StripeEventHandler {

    protected final PaymentRepository payments;

    /**
     * Where this service announces what it has learned.
     *
     * <p>Publishing from inside a handler is what makes an announcement trustworthy: the handler
     * runs in the sweep's transaction, so the outbox row and the change it describes commit
     * together. An event published after the commit could describe a change that was rolled
     * back.</p>
     */
    protected final EventPublisher events;

    /**
     * The Stripe object an event is about.
     *
     * <p>{@code getObject()} returns <strong>empty</strong> whenever the event's API version is not
     * the one the library pins - which happens when the endpoint in Stripe's dashboard is
     * registered at an older version, and is invisible until a handler quietly stops finding
     * anything. So an empty result falls back to deserialising anyway, with a warning naming the
     * version, because the alternative is a service that silently applies nothing.</p>
     *
     * @param event the event
     * @param type what the data object should be
     * @param <T> that type
     * @return the object
     * @throws IllegalStateException if the event's data object cannot be read at all, or is not of
     *         the expected type - both of which mean the handler is registered against the wrong
     *         event type and should fail rather than guess
     */
    protected <T extends StripeObject> T objectOf(Event event, Class<T> type) {
        Optional<StripeObject> deserialized = event.getDataObjectDeserializer().getObject();

        StripeObject object;
        if (deserialized.isPresent()) {
            object = deserialized.get();
        } else {
            log.warn("Stripe event {} is at API version {} but this service pins {}; "
                            + "deserialising it anyway. Re-register the webhook endpoint at the "
                            + "pinned version.",
                    event.getId(), event.getApiVersion(), com.stripe.Stripe.API_VERSION);
            try {
                object = event.getDataObjectDeserializer().deserializeUnsafe();
            } catch (Exception e) {
                throw new IllegalStateException(
                        "Could not read the data object of Stripe event %s (%s)"
                                .formatted(event.getId(), event.getType()), e);
            }
        }

        if (!type.isInstance(object)) {
            throw new IllegalStateException(
                    "Stripe event %s (%s) carries a %s, not a %s"
                            .formatted(event.getId(), event.getType(),
                                    object.getClass().getSimpleName(), type.getSimpleName()));
        }
        return type.cast(object);
    }

    /**
     * The payment a Stripe payment intent belongs to, if the payment should be changed by this
     * event.
     *
     * @param ledgerRow the event being applied
     * @param paymentIntentId Stripe's {@code pi_} identifier
     * @return the payment, or empty when there is nothing to do
     */
    protected Optional<Payment> byIntent(StripeEvent ledgerRow, String paymentIntentId) {
        return guard(ledgerRow, locateByIntent(ledgerRow, paymentIntentId));
    }

    /**
     * The payment a Checkout session belongs to, if the payment should be changed by this event.
     *
     * <p>Tries the session's {@code client_reference_id} first, because that is this service's own
     * payment id - set when the session was created, and therefore known to be right. The session
     * id is the fallback, and it is a fallback rather than the primary for a specific failure: the
     * row is committed before Stripe is called, so a session whose id was never written back to its
     * row is reachable, and looking up by session id alone would lose exactly those payments.</p>
     *
     * @param ledgerRow the event being applied
     * @param sessionId Stripe's {@code cs_} identifier
     * @param clientReferenceId what the session carries as its client reference, or null
     * @return the payment, or empty when there is nothing to do
     */
    protected Optional<Payment> bySession(
            StripeEvent ledgerRow, String sessionId, String clientReferenceId) {

        Optional<Payment> byReference = byClientReference(clientReferenceId);
        if (byReference.isPresent()) {
            return guard(ledgerRow, byReference);
        }
        return guard(ledgerRow, found(ledgerRow,
                payments.findByStripeCheckoutSessionId(sessionId), "checkout session " + sessionId));
    }

    private Optional<Payment> byClientReference(String clientReferenceId) {
        if (clientReferenceId == null) {
            return Optional.empty();
        }
        try {
            return payments.findById(java.util.UUID.fromString(clientReferenceId));
        } catch (IllegalArgumentException e) {
            // A session created by something other than this service, whose reference means
            // something else. Not an error: the session id lookup is the right path for it.
            log.debug("Checkout session carries a client reference that is not a payment id: {}",
                    clientReferenceId);
            return Optional.empty();
        }
    }

    /**
     * The payment an intent belongs to, <strong>without</strong> the staleness guard.
     *
     * <p>For a handler whose event is about something else attached to the payment - a refund,
     * say - which carries its own {@code lastStripeEventAt}. Guarding such an event on the
     * payment's timestamp would drop it whenever a later payment event had already been applied,
     * losing a refund because a charge succeeded after it.</p>
     *
     * @param ledgerRow the event being applied
     * @param paymentIntentId Stripe's {@code pi_} identifier
     * @return the payment, or empty if this service has no row for it
     */
    protected Optional<Payment> locateByIntent(StripeEvent ledgerRow, String paymentIntentId) {
        if (paymentIntentId == null) {
            log.warn("Stripe event {} ({}) names no payment intent", ledgerRow.getStripeEventId(),
                    ledgerRow.getEventType());
            return Optional.empty();
        }
        return found(ledgerRow, payments.findByStripePaymentIntentId(paymentIntentId),
                "payment intent " + paymentIntentId);
    }

    /**
     * Drops a payment this service does not know about, and one the event is too old for.
     *
     * <p><strong>A missing row is a warning, not a failure.</strong> Retrying will not conjure one,
     * and failing would park an event that is correct to do nothing about - so the event is marked
     * applied and the log names the object, which with the ledger row is the whole audit trail. It
     * means one of two things: the charge was created outside this service, in Stripe's dashboard
     * or by another integration; or this service created it and the row did not commit, which
     * cannot happen on the checkout path because the row commits before the payer is ever
     * redirected.</p>
     *
     * @param ledgerRow the event being applied
     * @param found what the lookup returned
     * @param what the thing being looked up, for the log
     * @return the payment, or empty
     */
    private Optional<Payment> found(StripeEvent ledgerRow, Optional<Payment> payment, String what) {
        if (payment.isEmpty()) {
            log.warn("No payment in this service for {}; Stripe event {} ({}) applies to nothing",
                    what, ledgerRow.getStripeEventId(), ledgerRow.getEventType());
        }
        return payment;
    }

    private Optional<Payment> guard(StripeEvent ledgerRow, Optional<Payment> payment) {
        if (payment.isPresent() && isStale(ledgerRow, payment.get().getLastStripeEventAt())) {
            log.info("Ignoring Stripe event {} ({}) for payment {}: it occurred at {}, and {} has "
                            + "already been applied",
                    ledgerRow.getStripeEventId(), ledgerRow.getEventType(), payment.get().getId(),
                    ledgerRow.getEventCreatedAt(), payment.get().getLastStripeEventAt());
            return Optional.empty();
        }
        return payment;
    }

    /**
     * Whether an event is older than what has already been applied to a row.
     *
     * <p>Takes the timestamp rather than the row, because the row is not always a payment: a
     * refund has its own, and a handler must compare against the one belonging to what it is
     * about to change.</p>
     *
     * <p>Not {@code before}, but {@code !isAfter}: two events with the same timestamp are the same
     * moment, and Stripe's are only to the second - so a strict comparison would apply both and let
     * whichever came second win, which is the coin toss this guard exists to avoid.</p>
     *
     * @param ledgerRow the event being applied
     * @param lastApplied when the newest event already applied to that row occurred, or null
     * @return true if this event should be left alone
     */
    protected boolean isStale(StripeEvent ledgerRow, java.time.Instant lastApplied) {
        return lastApplied != null
                && ledgerRow.getEventCreatedAt() != null
                && !ledgerRow.getEventCreatedAt().isAfter(lastApplied);
    }

    /**
     * Stamps the event's time on the payment and saves it.
     *
     * <p>Which is what makes {@link #isStale} work for the next event, so every handler ends with
     * this rather than with its own {@code save}.</p>
     *
     * @param payment the row to save
     * @param ledgerRow the event that changed it
     */
    protected void save(Payment payment, StripeEvent ledgerRow) {
        payment.setLastStripeEventAt(ledgerRow.getEventCreatedAt());
        payments.save(payment);
    }

    /**
     * Announces a payment's change of state, if it changed into one worth announcing.
     *
     * <p><strong>On the transition, never on the event.</strong> Stripe says the same thing more
     * than once - a checkout session completing and its payment intent succeeding both mean paid -
     * so a handler that announced whenever it ran would send two receipts for one payment. Every
     * caller passes the status the payment had before it touched it, and nothing is published when
     * that matches the status now.</p>
     *
     * <p>Called after {@link #save}, so the row the event describes is already written. The outbox
     * entry joins the same transaction, so the two commit together or neither does.</p>
     *
     * @param payment the payment as it now stands
     * @param before the status it had before this handler ran
     */
    protected void announce(Payment payment, PaymentStatus before) {
        if (payment.getStatus() == before) {
            return;
        }

        switch (payment.getStatus()) {
            case SUCCEEDED -> {
                UUID eventId = events.publish(payment.getId(), new PaymentSucceededEvent(
                        payment.getId(),
                        payment.getFirebaseUid(),
                        payment.getAmount(),
                        payment.getCurrency(),
                        payment.getOrderReferenceType(),
                        payment.getOrderReferenceId(),
                        sellerOf(payment)));
                log.info("Announced payment.succeeded [payment={}, event={}]",
                        payment.getId(), eventId);
            }
            case FAILED -> {
                UUID eventId = events.publish(payment.getId(), new PaymentFailedEvent(
                        payment.getId(),
                        payment.getFirebaseUid(),
                        payment.getAmount(),
                        payment.getCurrency(),
                        payment.getFailureCode(),
                        payment.getFailureMessage(),
                        payment.getOrderReferenceType(),
                        payment.getOrderReferenceId()));
                log.info("Announced payment.failed [payment={}, event={}]", payment.getId(), eventId);
            }
            default -> {
                // PROCESSING, CANCELED and REQUIRES_PAYMENT are announced to nobody. A payment
                // that was abandoned is the ordinary end of most checkouts, and an event for it
                // would be noise in every consumer's queue.
            }
        }
    }

    /**
     * Whose sale a payment is, or null for a payment to the platform itself.
     *
     * <p>A field on the row now, copied from the connected account when the payment was recorded.
     * It used to be a walk through a lazy association that was safe here and nowhere else, because
     * a handler runs inside the sweep's transaction - a distinction nobody should have to keep in
     * mind to publish an event.</p>
     *
     * @param payment the payment
     * @return the seller's uid, or null
     */
    private String sellerOf(Payment payment) {
        return payment.getSellerFirebaseUid();
    }
}
