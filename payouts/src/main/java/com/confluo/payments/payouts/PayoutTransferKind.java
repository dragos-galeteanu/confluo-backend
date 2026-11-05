package com.confluo.payments.payouts;

/**
 * Which leg of the money's journey out a row records.
 *
 * <p>Three different movements that all look like "money left", and separating them is what makes
 * a connected account's balance explainable. Under destination charges the platform is the merchant
 * of record, so the first two are not the same event happening twice: a transfer moves a sale's
 * share onto the account's Stripe balance, and a payout moves that balance to their bank, on
 * Stripe's schedule and usually days later.</p>
 */
public enum PayoutTransferKind {

    /** a sale's share moved from the platform's balance onto the connected account's */
    TRANSFER,

    /** a connected account's Stripe balance moved to their own bank */
    PAYOUT,

    /**
     * a transfer taken back, which is how the platform recovers its share of a refund or a lost
     * dispute. It can fail for want of balance, which is the whole risk of being merchant of record.
     */
    REVERSAL
}
