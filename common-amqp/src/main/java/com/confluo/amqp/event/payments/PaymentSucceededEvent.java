package com.confluo.amqp.event.payments;

import com.confluo.amqp.event.DomainEvent;

import java.util.UUID;

/**
 * Money has arrived for something.
 *
 * <p>The only event anything may deliver on. Published when a payment first reaches its succeeded
 * state - not when a checkout session completes, which for a method that settles later means only
 * that the payer finished the form.</p>
 *
 * <p>It carries an amount and a currency, where an event in {@code ..event.platform} would carry
 * an id and nothing else - and the reason is the consumer. The notifications service must be able
 * to word a receipt without calling back into payments, because a send that depended on payments
 * being up would mean a payments outage stopped mail going out. So this and its siblings carry what
 * a message needs to be <strong>worded</strong>, and nothing more: nothing about the person beyond
 * their uid, since names and addresses belong to whoever owns users and a copy of them would go
 * stale sitting in an outbox. No timestamp, event id or aggregate id either - {@code EventEnvelope}
 * carries all three, and the uid here is whose money it was rather than the envelope's
 * {@code actorId}, because nobody caused this: Stripe did.</p>
 *
 * <p>{@code orderReferenceType} and {@code orderReferenceId} are what was bought, as the service
 * that owns orders names it, and are null for a payment to the platform itself such as a
 * subscription invoice. They come back exactly as they were given: this service never interprets
 * them, and a consumer that recognises its own reference is the one meant to act.</p>
 *
 * @param paymentId the payment, which is also the envelope's aggregate id
 * @param firebaseUid who paid
 * @param amount what was collected, in the currency's minor units
 * @param currency uppercase ISO 4217
 * @param orderReferenceType what kind of thing was paid for, or null
 * @param orderReferenceId its id in the service that owns it, or null
 * @param sellerFirebaseUid whose sale it was, or null for a payment to the platform itself
 */
@DomainEvent(aggregate = "payment", event = "succeeded")
public record PaymentSucceededEvent(
        UUID paymentId,
        String firebaseUid,
        long amount,
        String currency,
        String orderReferenceType,
        String orderReferenceId,
        String sellerFirebaseUid) {
}
