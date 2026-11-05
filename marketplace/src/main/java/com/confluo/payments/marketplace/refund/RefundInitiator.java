package com.confluo.payments.marketplace.refund;

/**
 * Who decided a refund should happen.
 *
 * <p>Recorded for the same reason {@code RecipientSource} is in the notifications service: a row
 * that turns out to be wrong is only answerable with its provenance, and "why was this refunded?"
 * is a question asked about money rather than about a message. Nothing branches on it.</p>
 */
public enum RefundInitiator {

    /** a person with the Support or Admin role, through this service's own API */
    SUPPORT,

    /** an event from the service that owns orders, such as a cancellation */
    ORDER_EVENT,

    /**
     * the payer, undoing their own payment without approval.
     *
     * <p><strong>Nothing produces this today.</strong> Refunding is support's or an order
     * cancellation's; a payer who wants their money back cancels the order, which arrives as
     * {@link #ORDER_EVENT}. The reason is that deciding whether a payer may undo a payment needs to
     * know whether what they bought has been delivered, and this service holds an order reference
     * it never resolves - so the rule belongs where it can be evaluated. See
     * {@code RefundAuthorizationPolicy}.</p>
     *
     * <p>Kept, in this enum and in the schema's own check constraint, because that decision is
     * about where the rule lives rather than about whether a payer may ever refund anything -
     * and reinstating it should not need a migration.</p>
     */
    USER_REQUEST,

    /**
     * nobody in this platform: the refund was made in Stripe's own dashboard, and this service
     * learned about it from a webhook.
     *
     * <p>A real provenance and not a fallback. Support staff do use the dashboard, and recording
     * such a refund as {@link #SUPPORT} would claim it went through this service's API - which is
     * exactly the wrong answer to "where did this come from?", because it sends somebody looking
     * for a request that was never made.</p>
     */
    STRIPE_DASHBOARD
}
