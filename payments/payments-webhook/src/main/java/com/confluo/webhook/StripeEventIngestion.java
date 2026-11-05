package com.confluo.webhook;

/**
 * What became of a webhook this service accepted.
 *
 * <p>Both outcomes are answered with a 200, which is the part worth being deliberate about: Stripe
 * retries anything that is not a 2xx, so telling it that an event it has already delivered was a
 * duplicate would have it keep trying to deliver it. The distinction exists for the log and for
 * whoever is watching {@code stripe listen}, not for Stripe.</p>
 */
public enum StripeEventIngestion {

    /** first delivery: a ledger row was written, and something will apply it */
    RECORDED,

    /**
     * already in the ledger, so nothing was done. The ordinary outcome of a Stripe retry, and of
     * two instances receiving the same redelivery at once.
     */
    DUPLICATE
}
