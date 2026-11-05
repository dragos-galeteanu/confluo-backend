package com.confluo.payments.identity;

import com.confluo.webhook.StripeEvent;
import com.confluo.webhook.StripeEventHandler;
import com.confluo.stripe.IdentityVerificationGateway;
import com.stripe.model.Event;
import com.stripe.model.StripeObject;
import com.stripe.model.identity.VerificationSession;
import lombok.extern.slf4j.Slf4j;

import java.util.Optional;


/**
 * Stripe has decided something about somebody's documents.
 *
 * <p>One class for four event types, as the invoice handler is: every one of them says the same
 * thing - here is the session as it now stands - and the status comes off the session rather than
 * from which event carried it. {@code IdentityHandlerConfiguration} registers one bean per
 * type.</p>
 *
 * <h2>No timestamp guard, and why none is needed</h2>
 *
 * <p>Every other mirroring handler compares the event's time against the row's, because a payment
 * or a subscription moves between states that can legitimately be revisited. A verification session
 * does not: {@code verified} and {@code canceled} are <strong>terminal</strong> at Stripe, so a
 * late {@code processing} arriving after {@code verified} is not an older view of a changing thing,
 * it is an event about a session that has finished.</p>
 *
 * <p>So the guard here is the invariant itself - never leave a terminal state - which is both
 * stronger than a timestamp comparison and the reason {@code identity_verifications} needs no
 * {@code last_stripe_event_at} column.</p>
 */
@Slf4j
public class IdentityVerificationHandler implements StripeEventHandler {

    private final String eventType;
    private final IdentityVerificationRepository verifications;

    /**
     * @param eventType which Stripe event this bean handles
     * @param verifications the mirrored verification attempts
     */
    public IdentityVerificationHandler(
            String eventType, IdentityVerificationRepository verifications) {
        this.eventType = eventType;
        this.verifications = verifications;
    }

    @Override
    public String eventType() {
        return eventType;
    }

    @Override
    public void handle(StripeEvent ledgerRow, Event event) {
        VerificationSession session = sessionOf(event);

        Optional<IdentityVerification> found =
                verifications.findByStripeVerificationSessionId(session.getId());

        IdentityVerification attempt;
        if (found.isPresent()) {
            attempt = found.get();
            if (isTerminal(attempt.getStatus())) {
                log.info("Ignoring Stripe event {} for verification session {}: it is already {}, "
                                + "which is final",
                        ledgerRow.getStripeEventId(), session.getId(), attempt.getStatus());
                return;
            }
        } else {
            // Reachable: this service created the session at Stripe and then failed to record it.
            // The session's client reference is this platform's own uid, put there for exactly
            // this - without it the event would name somebody nothing here could identify.
            String firebaseUid = session.getClientReferenceId();
            if (firebaseUid == null) {
                log.warn("Stripe reported {} for verification session {}, which this service does "
                                + "not have and which names nobody",
                        eventType, session.getId());
                return;
            }
            attempt = new IdentityVerification();
            attempt.setFirebaseUid(firebaseUid);
            attempt.setStripeVerificationSessionId(session.getId());
            log.info("Recording verification session {} from its webhook; it was created at Stripe "
                    + "but never written down here", session.getId());
        }

        IdentityVerificationService.applyTo(attempt, snapshot(session));
        IdentityVerification saved = verifications.save(attempt);

        if (saved.getStatus() == IdentityVerificationStatus.VERIFIED) {
            log.info("Identity verified [uid={}, session={}]",
                    saved.getFirebaseUid(), saved.getStripeVerificationSessionId());
        } else {
            log.info("Identity check updated [uid={}, session={}, status={}, error={}]",
                    saved.getFirebaseUid(), saved.getStripeVerificationSessionId(),
                    saved.getStatus(), saved.getLastErrorCode());
        }
    }

    /**
     * Whether a status is one Stripe will not move away from.
     *
     * @param status the row's current status
     * @return true if it is final
     */
    private boolean isTerminal(IdentityVerificationStatus status) {
        return status == IdentityVerificationStatus.VERIFIED
                || status == IdentityVerificationStatus.CANCELED;
    }

    private IdentityVerificationGateway.VerificationSessionSnapshot snapshot(
            VerificationSession session) {
        VerificationSession.LastError error = session.getLastError();
        return new IdentityVerificationGateway.VerificationSessionSnapshot(
                session.getId(),
                session.getStatus(),
                session.getUrl(),
                error == null ? null : error.getCode(),
                error == null ? null : error.getReason());
    }

    private VerificationSession sessionOf(Event event) {
        Optional<StripeObject> deserialized = event.getDataObjectDeserializer().getObject();
        StripeObject object = deserialized.orElseGet(() -> {
            log.warn("Stripe event {} is at API version {} but this service pins {}; "
                            + "deserialising it anyway",
                    event.getId(), event.getApiVersion(), com.stripe.Stripe.API_VERSION);
            try {
                return event.getDataObjectDeserializer().deserializeUnsafe();
            } catch (Exception e) {
                throw new IllegalStateException(
                        "Could not read the data object of Stripe event %s".formatted(event.getId()), e);
            }
        });
        if (!(object instanceof VerificationSession session)) {
            throw new IllegalStateException(
                    "Stripe event %s (%s) carries a %s, not a VerificationSession".formatted(
                            event.getId(), event.getType(), object.getClass().getSimpleName()));
        }
        return session;
    }
}
