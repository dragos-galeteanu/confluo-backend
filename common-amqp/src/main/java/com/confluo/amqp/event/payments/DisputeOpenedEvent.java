package com.confluo.amqp.event.payments;

import com.confluo.amqp.event.DomainEvent;

import java.time.Instant;
import java.util.UUID;

/**
 * A payer has charged back.
 *
 * <p><strong>The most urgent thing this service announces.</strong> A dispute has a deadline: miss
 * it and the dispute is lost by default, with no further opportunity to say anything. So this event
 * exists to get a person's attention, and {@code evidenceDueBy} is the field that matters.</p>
 *
 * <p>Under destination charges this platform is the merchant of record, which means the money comes
 * out of the <strong>platform's</strong> balance, not the seller's. Whether and how much is clawed
 * back from the seller is a separate decision, and this event deliberately says nothing about it:
 * a consumer that assumed the seller had already been debited would tell them something untrue.</p>
 *
 * @param disputeId the dispute, which is also the envelope's aggregate id
 * @param paymentId the payment being disputed
 * @param firebaseUid who paid, and is now disputing
 * @param sellerFirebaseUid whose sale it was, or null for a payment to the platform itself
 * @param amount what is being claimed back, in minor units
 * @param currency uppercase ISO 4217
 * @param reason Stripe's reason, e.g. {@code fraudulent} or {@code product_not_received}
 * @param evidenceDueBy the deadline for answering it, or null if Stripe has set none
 * @param orderReferenceType what was bought, or null
 * @param orderReferenceId its id in the service that owns it, or null
 */
@DomainEvent(aggregate = "dispute", event = "opened")
public record DisputeOpenedEvent(
        UUID disputeId,
        UUID paymentId,
        String firebaseUid,
        String sellerFirebaseUid,
        long amount,
        String currency,
        String reason,
        Instant evidenceDueBy,
        String orderReferenceType,
        String orderReferenceId) {
}
