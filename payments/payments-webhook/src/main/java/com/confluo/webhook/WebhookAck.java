package com.confluo.webhook;


/**
 * What the webhook endpoint answers with.
 *
 * <p>Stripe discards the body and reads only the status, so this exists for the person watching
 * {@code stripe listen} during development - where "recorded" and "duplicate" is the difference
 * between a working endpoint and one that is quietly rejecting everything as already seen.</p>
 *
 * @param outcome whether a row was written, or the event was already known
 */
public record WebhookAck(StripeEventIngestion outcome) {
}
