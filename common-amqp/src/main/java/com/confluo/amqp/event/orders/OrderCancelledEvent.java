package com.confluo.amqp.event.orders;

import com.confluo.amqp.event.DomainEvent;

/**
 * An order will not be delivered after all.
 *
 * <p>A statement, not an instruction. It says the order is off; it does not say "refund this", and
 * deliberately so - whether anything goes back, how much, and whether the seller's share is clawed
 * back are decisions the payments service owns, because it is the one holding the charge. An orders
 * service that asked for a refund would be deciding money policy from outside the service that
 * implements it.</p>
 *
 * <p>Published by an orders service that does not exist yet, and the contract is here anyway:
 * a record in common-amqp is what lets the consuming side be written, deployed and tested before
 * the producing side exists, and it is the producer's queue name and exchange - not the class's
 * location - that decide who may publish it. The alternative, waiting, would mean the first version
 * of the orders service inventing a payload that payments then has to accept.</p>
 *
 * <p>Cancelled covers every way an order stops: the buyer changed their mind, the seller could not
 * fulfil it, support unwound it, a deadline passed. {@code reason} is free text for a person
 * reading it later, and nothing branches on it - a consumer that needs to behave differently per
 * reason wants its own event.</p>
 *
 * <h2>What a consumer must expect</h2>
 *
 * <p><strong>An order that was never paid.</strong> Most cancellations are of orders that never got
 * as far as a payment, so the normal outcome of consuming this is that there is nothing to do.</p>
 *
 * <p><strong>Redelivery, and a second cancellation of the same order.</strong> The consumed-message
 * ledger stops the same {@code eventId} being handled twice, and that is all it stops. Two
 * cancellation events for one order are two different events, so a consumer that moves money on
 * this needs a guard of its own keyed by the order rather than by the event.</p>
 *
 * @param orderReferenceType what kind of thing was ordered, as this service names it - the same
 *                           value the payments service holds on the payment and never interprets
 * @param orderReferenceId its id in this service
 * @param buyerFirebaseUid who ordered it. Not needed to find the payment, which the reference pair
 *                         identifies on its own: it is here as a cross-check, so a consumer can
 *                         notice that the order it was pointed at belongs to somebody else rather
 *                         than quietly refunding the wrong person
 * @param refundAmount how much of the payment should be considered undone, in the currency's minor
 *                     units, or <strong>null</strong> for all of it. A stated amount is for a
 *                     cancellation that is not total - a restocking fee kept back, one line of
 *                     several cancelled - and is in the payment's currency, which this service
 *                     does not restate because it does not own it
 * @param reason why it was cancelled, in whatever words the cancelling party used
 */
@DomainEvent(aggregate = "order", event = "cancelled")
public record OrderCancelledEvent(
        String orderReferenceType,
        String orderReferenceId,
        String buyerFirebaseUid,
        Long refundAmount,
        String reason) {
}
