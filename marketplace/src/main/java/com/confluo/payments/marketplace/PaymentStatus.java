package com.confluo.payments.marketplace;

/**
 * Where a payment has got to.
 *
 * <p>This service's own reduction of Stripe's PaymentIntent statuses, not a copy of them. Stripe
 * distinguishes {@code requires_payment_method}, {@code requires_confirmation},
 * {@code requires_action} and {@code requires_capture}, and the difference between them is a
 * question about the payer's browser - which is Checkout's business and not this service's. What is
 * worth storing is whether the money is still coming, has arrived, or never will.</p>
 *
 * <p>{@link #SUCCEEDED} is the only state in which anything may be delivered, and it is set by a
 * webhook rather than by the request that created the session: a payer who closes the tab at the
 * right moment leaves a session that completed and a charge that did not.</p>
 */
public enum PaymentStatus {

    /** created, and waiting for the payer to do something. Every payment starts here */
    REQUIRES_PAYMENT,

    /**
     * the payer has paid and the money is in transit. Not all methods settle immediately, and
     * treating this as success is how a bank debit that later fails gets delivered for free.
     */
    PROCESSING,

    /** the money is there. The only state anything may be delivered on */
    SUCCEEDED,

    /** abandoned or expired before it was paid, which is the ordinary end of most sessions */
    CANCELED,

    /** the payer's method was declined or the charge failed outright */
    FAILED
}
