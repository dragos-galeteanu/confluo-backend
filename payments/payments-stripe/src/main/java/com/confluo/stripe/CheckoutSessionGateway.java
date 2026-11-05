package com.confluo.stripe;

import com.stripe.param.checkout.SessionCreateParams;

import java.util.Optional;

/**
 * The two things this service does to a Stripe Checkout session.
 *
 * <p>A seam, and a narrow one. The parameters stay Stripe's own type, because what goes into a
 * session - the destination, the application fee, the tax liability - is where every decision in
 * this part of the service actually lives, and wrapping it in a parallel set of records would hide
 * exactly the part worth reading. What is behind the seam is only the call and the mapping of what
 * comes back.</p>
 *
 * <p>Which is enough to let the checkout logic - the fee, the idempotent resume, the row's
 * lifecycle - be exercised against a stub rather than against Stripe's live API, and that matters
 * here more than anywhere else in the service: the thing being tested is money.</p>
 */
public interface CheckoutSessionGateway {

    /**
     * Creates a session, or hands back the one an identical earlier request made.
     *
     * @param params what the session should be
     * @param idempotencyKey this service's own key for the attempt, so a retry does not make a
     *                       second session for the same sale
     * @return what Stripe created
     */
    CheckoutSession create(SessionCreateParams params, String idempotencyKey);

    /**
     * A session, if it is still payable.
     *
     * <p>Empty for one that has expired or been completed, because the only thing the caller wants
     * it for is to send a payer back to it - and a URL that no longer accepts payment is worse than
     * no URL at all.</p>
     *
     * @param sessionId Stripe's {@code cs_} identifier
     * @return the open session, or empty
     */
    Optional<CheckoutSession> findOpen(String sessionId);

    /**
     * What Stripe says about a session.
     *
     * @param id Stripe's {@code cs_} identifier
     * @param url where the payer is sent to pay
     * @param status Stripe's own: {@code open}, {@code complete} or {@code expired}
     */
    record CheckoutSession(String id, String url, String status) {

        /** Stripe's value for a session that can still be paid. */
        public static final String OPEN = "open";

        /** @return whether a payer can still be sent to it */
        public boolean isOpen() {
            return OPEN.equals(status);
        }
    }
}
