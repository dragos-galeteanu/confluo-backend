package com.confluo.payments.marketplace.refund.dto;

/**
 * Why a refund is being made, in the only three terms Stripe accepts.
 *
 * <p>This is not the human reason - {@code notes} on the request is - and nothing in this service
 * branches on it. It goes to Stripe, where it feeds their fraud and dispute analytics: a refund
 * marked {@link #FRAUDULENT} tells Stripe the charge should never have happened, which affects how
 * they assess the account, and marking an ordinary cancellation that way misinforms them.</p>
 *
 * <p>A type of this service's own rather than {@code RefundCreateParams.Reason} directly, so that
 * Stripe's enum does not become part of this platform's HTTP API: these three names are then this
 * service's promise, and a renamed constant in a future Stripe SDK is a compile error here rather
 * than a silently broken request body.</p>
 */
public enum RefundReason {

    /** the payer was charged twice for one thing */
    DUPLICATE,

    /** the charge was not made by the cardholder */
    FRAUDULENT,

    /**
     * the ordinary case: somebody asked for their money back.
     *
     * <p>What every cancelled order is, and the default when a request says nothing - the two
     * alternatives are both claims about the charge that should only be made deliberately.</p>
     */
    REQUESTED_BY_CUSTOMER
}
