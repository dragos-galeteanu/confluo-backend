package com.confluo.amqp.event.payments;

import com.confluo.amqp.event.DomainEvent;

import java.time.Instant;
import java.util.UUID;

/**
 * An invoice could not be collected, and Stripe will try again.
 *
 * <p>The dunning event. It fires on every failed attempt, not just the first, which is deliberate:
 * a reminder sequence usually escalates, and the attempt number is what a consumer escalates on.
 * The retry schedule is Stripe Billing's, so {@code nextPaymentAttempt} is the only honest answer
 * to "when will you try again" and is carried for that reason.</p>
 *
 * <p>A null {@code nextPaymentAttempt} means Stripe has stopped trying - the last reminder before
 * the subscription lapses, and the one worth wording differently.</p>
 *
 * @param invoiceId the invoice, which is also the envelope's aggregate id
 * @param firebaseUid who owes it
 * @param subscriptionId the subscription it is a cycle of, or null
 * @param number Stripe's human-readable number
 * @param amountDue what is owed, in minor units
 * @param currency uppercase ISO 4217
 * @param attemptCount how many times Stripe has tried, counting this one
 * @param nextPaymentAttempt when it will try again, or null if it has given up
 * @param hostedInvoiceUrl Stripe's hosted copy, where the payer can pay it themselves
 */
@DomainEvent(aggregate = "invoice", event = "payment_failed")
public record InvoicePaymentFailedEvent(
        UUID invoiceId,
        String firebaseUid,
        UUID subscriptionId,
        String number,
        long amountDue,
        String currency,
        int attemptCount,
        Instant nextPaymentAttempt,
        String hostedInvoiceUrl) {
}
