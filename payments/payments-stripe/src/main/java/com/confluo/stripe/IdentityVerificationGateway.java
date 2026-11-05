package com.confluo.stripe;

/**
 * The two things this service does to a Stripe Identity verification session.
 *
 * <p>A seam, as the checkout and Connect gateways are. What is in front of it - when a new session
 * is started rather than an old one resumed, and what each status means for the person - is the
 * part worth exercising; what is behind it is two network calls.</p>
 */
public interface IdentityVerificationGateway {

    /**
     * Starts a check.
     *
     * @param firebaseUid whose identity it is, carried as the session's client reference so a
     *                    webhook can find the person even if this service failed to record the
     *                    session
     * @param stripeCustomerId the person's Stripe customer, or null if they have none. Passed to
     *                         Stripe as the related customer, which is what lets Stripe weigh this
     *                         check against the rest of what it knows about them
     * @param returnUrl where Stripe sends them once the documents are submitted. Passed in rather
     *                  than read from configuration here, for the same reason the connect gateway
     *                  takes its URLs: where this platform's pages live is not Stripe's concern
     *                  and not this seam's
     * @return the session as Stripe created it
     */
    VerificationSessionSnapshot create(
            String firebaseUid, String stripeCustomerId, String returnUrl);

    /**
     * Reads a session from Stripe.
     *
     * <p>Used to get a usable URL for a session already under way: the one handed out earlier
     * cannot be assumed to still be in anybody's hands.</p>
     *
     * @param stripeVerificationSessionId Stripe's {@code vs_} identifier
     * @return the session as Stripe has it
     */
    VerificationSessionSnapshot retrieve(String stripeVerificationSessionId);

    /**
     * What Stripe says about a verification session.
     *
     * <p>The client secret is deliberately absent. It would be a second way to complete the check,
     * and nothing in this platform needs one - the hosted URL is enough.</p>
     *
     * @param stripeVerificationSessionId Stripe's own identifier
     * @param status Stripe's own status string
     * @param url the hosted page, or null for a session that is no longer open
     * @param errorCode Stripe's code for the last failure, or null
     * @param errorReason Stripe's wording of it, or null
     */
    record VerificationSessionSnapshot(
            String stripeVerificationSessionId,
            String status,
            String url,
            String errorCode,
            String errorReason) {
    }
}
