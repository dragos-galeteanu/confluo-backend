package com.confluo.payments.marketplace;

import com.confluo.amqp.publish.EventPublisher;
import com.confluo.webhook.StripeEvent;
import com.stripe.model.Event;
import com.stripe.model.checkout.Session;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;


/**
 * The payer finished at Stripe's hosted page.
 *
 * <p><strong>The event that joins the two halves of a payment.</strong> When the session was
 * created this service knew only the session id - Stripe creates the payment intent later, when the
 * payer actually reaches the page - so this is the only moment the {@code cs_} and the {@code pi_}
 * are seen together. Without it, every later event about that intent would find no row, because
 * nothing else can make that link.</p>
 *
 * <p>It is also where the tax Stripe Tax calculated first becomes known, for the same reason: the
 * amount was not final until the payer entered an address.</p>
 *
 * <p>Completing is not the same as paying. {@code payment_status} is {@code unpaid} for a method
 * that settles later, so the payment is only marked succeeded when Stripe says it is paid - and
 * {@code payment_intent.succeeded} will say so again when it settles, which is harmless: the
 * timestamp guard drops it if it arrives out of order, and applying it twice sets the same values.
 * Treating completion as payment is how a bank debit that later fails gets delivered for free.</p>
 */
@Component
@Slf4j
public class CheckoutSessionCompletedHandler extends PaymentMirroringHandler {

    /** Stripe's own value: the session has been paid in full. */
    private static final String PAID = "paid";

    /**
     * @param payments the mirrored payments
     * @param events where a change of state is announced
     */
    public CheckoutSessionCompletedHandler(PaymentRepository payments, EventPublisher events) {
        super(payments, events);
    }

    @Override
    public String eventType() {
        return "checkout.session.completed";
    }

    @Override
    public void handle(StripeEvent ledgerRow, Event event) {
        Session session = objectOf(event, Session.class);

        bySession(ledgerRow, session.getId(), session.getClientReferenceId()).ifPresent(payment -> {
            PaymentStatus before = payment.getStatus();
            // The link, and the whole reason this handler is first.
            payment.setStripePaymentIntentId(session.getPaymentIntent());

            if (session.getTotalDetails() != null && session.getTotalDetails().getAmountTax() != null) {
                payment.setTaxAmount(session.getTotalDetails().getAmountTax());
            }
            // The total Stripe actually collected, which is what tax and any discount made of the
            // amount this service asked for.
            if (session.getAmountTotal() != null) {
                payment.setAmount(session.getAmountTotal());
            }

            if (PAID.equals(session.getPaymentStatus())) {
                payment.setStatus(PaymentStatus.SUCCEEDED);
                payment.setCapturedAt(Instant.now());
            } else {
                payment.setStatus(PaymentStatus.PROCESSING);
            }

            save(payment, ledgerRow);
            announce(payment, before);
            log.info("Checkout session completed [payment={}, intent={}, paymentStatus={}, status={}]",
                    payment.getId(), payment.getStripePaymentIntentId(),
                    session.getPaymentStatus(), payment.getStatus());
        });
    }
}
