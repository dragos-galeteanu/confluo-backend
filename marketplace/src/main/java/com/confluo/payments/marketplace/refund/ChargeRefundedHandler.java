package com.confluo.payments.marketplace.refund;

import com.confluo.amqp.publish.EventPublisher;
import com.confluo.webhook.StripeEvent;
import com.confluo.payments.marketplace.PaymentRepository;
import com.stripe.model.Charge;
import com.stripe.model.Event;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;


/**
 * Money went back to the payer.
 *
 * <p>The charge arrives carrying its whole refund history and a cumulative
 * {@code amount_refunded}, so this is both where refund rows come into existence and where a
 * payment learns how much of it has been given back.</p>
 *
 * <p><strong>The two halves are guarded differently</strong>, which is the only subtle thing here.
 * Each refund row is keyed by Stripe's own id and compared against its <em>own</em> timestamp, so
 * mirroring one is safe whatever else has happened to the payment. {@code amount_refunded} is a
 * running total on the payment, so writing an older event's value over a newer one would quietly
 * reduce it - and that half is guarded against the payment's timestamp instead.</p>
 *
 * <p>Guarding the whole handler on the payment would lose refunds: a charge refunded before a later
 * payment event was applied would find the payment "newer" and do nothing at all.</p>
 */
@Component
@Slf4j
public class ChargeRefundedHandler extends RefundMirroringHandler {

    /**
     * @param payments the mirrored payments
     * @param refunds the mirrored refunds
     * @param events where a refund is announced
     */
    public ChargeRefundedHandler(
            PaymentRepository payments, RefundRepository refunds, EventPublisher events) {
        super(payments, refunds, events);
    }

    @Override
    public String eventType() {
        return "charge.refunded";
    }

    @Override
    public void handle(StripeEvent ledgerRow, Event event) {
        Charge charge = objectOf(event, Charge.class);

        locateByIntent(ledgerRow, charge.getPaymentIntent()).ifPresent(payment -> {
            List<com.stripe.model.Refund> stripeRefunds = charge.getRefunds() == null
                    ? List.of()
                    : charge.getRefunds().getData();

            // This event carries the charge itself, so it is a second chance to record the id for
            // a payment whose payment_intent.succeeded was missed or arrived without one.
            if (payment.getStripeChargeId() == null && charge.getId() != null) {
                payment.setStripeChargeId(charge.getId());
            }

            List<Mirrored> mirrored = new java.util.ArrayList<>();
            for (com.stripe.model.Refund stripeRefund : stripeRefunds) {
                mirror(ledgerRow, payment, stripeRefund).ifPresent(mirrored::add);
            }

            // The running total, which is the half that needs the payment's own guard.
            if (!isStale(ledgerRow, payment.getLastStripeEventAt())
                    && charge.getAmountRefunded() != null) {
                payment.setAmountRefunded(charge.getAmountRefunded());
                save(payment, ledgerRow);
            } else {
                log.info("Leaving the refunded total on payment {} alone: event {} is not newer "
                                + "than what has been applied",
                        payment.getId(), ledgerRow.getStripeEventId());
            }

            // Announced last, once the payment's refunded total is current: the event says whether
            // the payment is now fully refunded, and that is only true of the total written above.
            mirrored.forEach(refund -> announce(refund, payment));

            log.info("Charge refunded [payment={}, charge={}, refunds={}, refundedTotal={}]",
                    payment.getId(), charge.getId(), mirrored.size(), payment.getAmountRefunded());
        });
    }
}
