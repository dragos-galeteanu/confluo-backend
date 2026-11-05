package com.confluo.stripe;

import com.stripe.StripeClient;
import com.stripe.exception.StripeException;
import com.stripe.model.identity.VerificationSession;
import com.stripe.net.RequestOptions;
import com.stripe.param.identity.VerificationSessionCreateParams;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * {@link IdentityVerificationGateway} against Stripe.
 *
 * <p>{@code type = DOCUMENT} asks for a photographic identity document and a selfie to compare it
 * against, which is what "is this person who they say they are" means here.
 * {@code ID_NUMBER} - checking a government number against a database - is a different question and
 * not the one this platform is asking.</p>
 *
 * <p><strong>No idempotency key, deliberately.</strong> A person may legitimately need several
 * attempts over time: a document that would not scan, one that expired, a re-check months later.
 * Keying on the uid would hand them back the session that already failed. What prevents accidental
 * duplicates is {@code IdentityVerificationService} looking at their latest attempt first, and a
 * race there costs an unused session that nobody is sent to.</p>
 */
@RequiredArgsConstructor
@Slf4j
public class StripeIdentityVerificationGateway implements IdentityVerificationGateway {

    private final StripeClient stripe;

    @Override
    public VerificationSessionSnapshot create(
            String firebaseUid, String stripeCustomerId, String returnUrl) {
        VerificationSessionCreateParams.Builder params = VerificationSessionCreateParams.builder()
                .setType(VerificationSessionCreateParams.Type.DOCUMENT)
                .setReturnUrl(returnUrl)
                // This platform's own name for the person, so that a webhook about a session this
                // service failed to record can still be attributed - the same trick a Checkout
                // session's client reference does for a payment.
                .setClientReferenceId(firebaseUid)
                .putMetadata("firebase_uid", firebaseUid);

        if (stripeCustomerId != null) {
            // Lets Stripe weigh this check against everything else it knows about the person.
            params.setRelatedCustomer(stripeCustomerId);
        }

        try {
            VerificationSession session = stripe.identity().verificationSessions()
                    .create(params.build(), RequestOptions.builder().build());
            log.info("Identity verification session created [uid={}, session={}, status={}]",
                    firebaseUid, session.getId(), session.getStatus());
            return snapshot(session);
        } catch (StripeException e) {
            throw new StripeOperationException(
                    "Could not start an identity check for %s".formatted(firebaseUid), e);
        }
    }

    @Override
    public VerificationSessionSnapshot retrieve(String stripeVerificationSessionId) {
        try {
            return snapshot(stripe.identity().verificationSessions()
                    .retrieve(stripeVerificationSessionId));
        } catch (StripeException e) {
            throw new StripeOperationException(
                    "Could not read identity verification session %s"
                            .formatted(stripeVerificationSessionId), e);
        }
    }

    private VerificationSessionSnapshot snapshot(VerificationSession session) {
        VerificationSession.LastError error = session.getLastError();
        return new VerificationSessionSnapshot(
                session.getId(),
                session.getStatus(),
                session.getUrl(),
                error == null ? null : error.getCode(),
                error == null ? null : error.getReason());
    }
}
