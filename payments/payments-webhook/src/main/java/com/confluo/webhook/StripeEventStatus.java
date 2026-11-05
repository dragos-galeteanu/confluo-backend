package com.confluo.webhook;

/**
 * Where a received Stripe event has got to in this service.
 *
 * <p>The webhook ledger's own state, not Stripe's. A row is written the moment an event is
 * accepted and before anything acts on it, so that the unique constraint on the event id - and not
 * a handler's own care - is what makes applying it twice impossible. Stripe retries a webhook for
 * days, and will redeliver an event it has already delivered.</p>
 *
 * <p>{@link #FAILED} is kept rather than cleared, and is the reason a sweep exists: an event that
 * threw is a bug to be fixed and then replayed, not a message to be lost. {@link #IGNORED} is
 * separate from {@link #PROCESSED} because "we received this and deliberately did nothing" is an
 * answer, and silence is not.</p>
 */
public enum StripeEventStatus {

    /** accepted, verified, recorded, and not yet applied */
    RECEIVED,

    /** applied */
    PROCESSED,

    /** accepted and deliberately not applied, because nothing here subscribes to this type */
    IGNORED,

    /** applying it threw. Left for the sweep to retry, with the error and the attempt count */
    FAILED
}
