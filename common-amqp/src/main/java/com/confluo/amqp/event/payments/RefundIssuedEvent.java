package com.confluo.amqp.event.payments;

import com.confluo.amqp.event.DomainEvent;

import java.util.UUID;

/**
 * Money has gone back to a payer.
 *
 * <p>Published when a refund first succeeds, not when it is created: a refund is submitted to the
 * payer's bank and can take days, or come back failed because the card has closed since. Announcing
 * it at creation would tell somebody their money was returned when it may not be.</p>
 *
 * <p>{@code fullyRefunded} saves every consumer the same arithmetic, and it is the thing most of
 * them actually branch on - a partially refunded order is usually still an order.</p>
 *
 * @param refundId the refund, which is also the envelope's aggregate id
 * @param paymentId what it came off
 * @param firebaseUid who is getting the money back
 * @param amount how much, in minor units
 * @param currency uppercase ISO 4217
 * @param fullyRefunded whether the whole payment has now been returned
 * @param orderReferenceType what was paid for, or null
 * @param orderReferenceId its id in the service that owns it, or null
 */
@DomainEvent(aggregate = "refund", event = "issued")
public record RefundIssuedEvent(
        UUID refundId,
        UUID paymentId,
        String firebaseUid,
        long amount,
        String currency,
        boolean fullyRefunded,
        String orderReferenceType,
        String orderReferenceId) {
}
