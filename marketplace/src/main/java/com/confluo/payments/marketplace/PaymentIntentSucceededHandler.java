package com.confluo.payments.marketplace;

import com.confluo.amqp.publish.EventPublisher;
import com.confluo.webhook.StripeEvent;
import com.stripe.model.Event;
import com.stripe.model.PaymentIntent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;


/**
 * The money arrived.
 *
 * <p>The only event anything may be delivered on, and the reason {@link PaymentStatus#SUCCEEDED}
 * exists as a state of its own rather than being inferred from a completed checkout. For a card it
 * follows the session's completion within the second; for a bank debit it can be days later.</p>
 *
 * <p>{@code amount_received} rather than {@code amount}: they differ when a payment was captured
 * for less than it was authorised for, and what the platform actually has is the former.</p>
 */
@Component
@Slf4j
public class PaymentIntentSucceededHandler extends PaymentMirroringHandler {

    /**
     * @param payments the mirrored payments
     * @param events where a change of state is announced
     */
    public PaymentIntentSucceededHandler(PaymentRepository payments, EventPublisher events) {
        super(payments, events);
    }

    @Override
    public String eventType() {
        return "payment_intent.succeeded";
    }

    @Override
    public void handle(StripeEvent ledgerRow, Event event) {
        PaymentIntent intent = objectOf(event, PaymentIntent.class);

        byIntent(ledgerRow, intent.getId()).ifPresent(payment -> {
            PaymentStatus before = payment.getStatus();
            payment.setStatus(PaymentStatus.SUCCEEDED);
            if (intent.getAmountReceived() != null && intent.getAmountReceived() > 0) {
                payment.setAmount(intent.getAmountReceived());
            }
            if (payment.getCapturedAt() == null) {
                payment.setCapturedAt(Instant.now());
            }
            // The charge that worked. Stored here because a transfer names a charge and nothing
            // else, so this is what later links a seller's payout back to this sale.
            if (intent.getLatestCharge() != null) {
                payment.setStripeChargeId(intent.getLatestCharge());
            }
            // A payment that has now succeeded should not still carry why an earlier attempt did
            // not: a payer whose first card was declined and whose second worked has one payment.
            payment.setFailureCode(null);
            payment.setFailureMessage(null);

            save(payment, ledgerRow);
            announce(payment, before);
            log.info("Payment succeeded [payment={}, intent={}, amount={} {}]",
                    payment.getId(), intent.getId(), payment.getAmount(), payment.getCurrency());
        });
    }
}
