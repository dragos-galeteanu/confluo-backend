package com.confluo.amqp.event.payments;

import com.confluo.amqp.event.DomainEvent;

import java.util.UUID;

/**
 * A dispute is over.
 *
 * <p>Published when Stripe settles it, either way, because the two outcomes want opposite handling:
 * a dispute <strong>won</strong> returns the money and the order stands; one <strong>lost</strong>
 * is money gone for good, and under destination charges it is gone from the platform's balance.</p>
 *
 * <p>{@code won} rather than a status string, because that is the only part a consumer branches on
 * and Stripe's own vocabulary here is awkward - {@code warning_closed} means a warning was
 * withdrawn before it became a dispute, which is not a win so much as nothing having happened.</p>
 *
 * @param disputeId the dispute, which is also the envelope's aggregate id
 * @param paymentId the payment that was disputed
 * @param firebaseUid who paid
 * @param sellerFirebaseUid whose sale it was, or null
 * @param amount what was claimed back, in minor units
 * @param currency uppercase ISO 4217
 * @param won whether the platform kept the money
 * @param transferReversedAmount how much was recovered from the seller, which can be less than
 *                               their share of a lost dispute if their balance had already been
 *                               paid out
 */
@DomainEvent(aggregate = "dispute", event = "closed")
public record DisputeClosedEvent(
        UUID disputeId,
        UUID paymentId,
        String firebaseUid,
        String sellerFirebaseUid,
        long amount,
        String currency,
        boolean won,
        long transferReversedAmount) {
}
