package com.confluo.payments.marketplace;

import com.confluo.amqp.publish.EventPublisher;
import com.confluo.webhook.StripeEvent;
import com.stripe.model.Event;
import com.stripe.model.checkout.Session;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * The payer never finished, and Stripe closed the session.
 *
 * <p>The ordinary end of most checkouts, and worth recording rather than leaving a row at
 * {@code REQUIRES_PAYMENT} forever: a payment that will never be paid and one that might still be
 * are different answers to "is this order waiting on money?", and only this event distinguishes
 * them.</p>
 *
 * <p>Refuses to cancel a payment that has succeeded. A session can expire after its intent was
 * paid - an asynchronous method settling just as the window closed - and Stripe's ordering
 * guarantees nothing, so without this check an expiry arriving late would move a paid payment to
 * cancelled. The timestamp guard in the base class covers the usual case; this covers the one where
 * the two events share a second.</p>
 */
@Component
@Slf4j
public class CheckoutSessionExpiredHandler extends PaymentMirroringHandler {

    /**
     * @param payments the mirrored payments
     * @param events where a change of state is announced
     */
    public CheckoutSessionExpiredHandler(PaymentRepository payments, EventPublisher events) {
        super(payments, events);
    }

    @Override
    public String eventType() {
        return "checkout.session.expired";
    }

    @Override
    public void handle(StripeEvent ledgerRow, Event event) {
        Session session = objectOf(event, Session.class);

        bySession(ledgerRow, session.getId(), session.getClientReferenceId()).ifPresent(payment -> {
            PaymentStatus before = payment.getStatus();
            if (payment.getStatus() == PaymentStatus.SUCCEEDED) {
                log.info("Checkout session {} expired but payment {} has already succeeded; "
                                + "leaving it alone",
                        session.getId(), payment.getId());
                return;
            }
            payment.setStatus(PaymentStatus.CANCELED);
            save(payment, ledgerRow);
            announce(payment, before);
            log.info("Checkout session expired [payment={}, session={}]",
                    payment.getId(), session.getId());
        });
    }
}
