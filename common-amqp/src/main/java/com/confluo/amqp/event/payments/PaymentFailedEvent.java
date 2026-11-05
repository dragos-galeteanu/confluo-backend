package com.confluo.amqp.event.payments;

import com.confluo.amqp.event.DomainEvent;

import java.util.UUID;

/**
 * A payment was refused.
 *
 * <p>Not necessarily the end of it: Stripe keeps the intent usable, so a payer who was declined can
 * try another card and succeed, and a {@code payment.succeeded} for the same payment may follow.
 * A consumer that cancels an order on this event will sometimes cancel one that was about to be
 * paid - waiting for the session to expire is the safer reading.</p>
 *
 * <p>{@code failureReason} is Stripe's own wording and the only text fit to show the payer;
 * {@code failureCode} is what anything automated should branch on.</p>
 *
 * @param paymentId the payment, which is also the envelope's aggregate id
 * @param firebaseUid who was paying
 * @param amount what was being collected, in minor units
 * @param currency uppercase ISO 4217
 * @param failureCode Stripe's code, e.g. {@code card_declined}
 * @param failureReason Stripe's wording of it, or null
 * @param orderReferenceType what was being paid for, or null
 * @param orderReferenceId its id in the service that owns it, or null
 */
@DomainEvent(aggregate = "payment", event = "failed")
public record PaymentFailedEvent(
        UUID paymentId,
        String firebaseUid,
        long amount,
        String currency,
        String failureCode,
        String failureReason,
        String orderReferenceType,
        String orderReferenceId) {
}
