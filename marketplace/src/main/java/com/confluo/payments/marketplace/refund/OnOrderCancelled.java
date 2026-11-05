package com.confluo.payments.marketplace.refund;

import com.confluo.amqp.consume.EventHandler;
import com.confluo.amqp.event.EventEnvelope;
import com.confluo.amqp.event.orders.OrderCancelledEvent;
import com.confluo.payments.marketplace.refund.dto.RefundResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;


/**
 * Gives the money back when an order is cancelled.
 *
 * <p>The first thing another service on this platform asks of payments, and the reason it asks by
 * publishing rather than by calling: interservice communication here is AMQP. This service's HTTP
 * routes are for people, authenticated by a Firebase token, and there is deliberately no second
 * kind of caller for them to authenticate - a service that needs something from payments publishes
 * a fact and lets payments decide what it means.</p>
 *
 * <p>Which is why {@code order.cancelled} says an order is off rather than asking for a refund.
 * Whether anything goes back, how much, and whether the seller's share is clawed back are decided
 * in {@code RefundService} and in {@code RefundProperties} - inside the service that holds
 * the charge, not by the one that cancelled the order.</p>
 *
 * <h2>Nothing to do is the usual outcome</h2>
 *
 * <p>Most cancelled orders were never paid for. {@code RefundService} answers that with an empty
 * list rather than an exception, and this returns normally: failing would park the message and ask
 * somebody to look at a cancellation that needed nothing.</p>
 *
 * <h2>What makes a second delivery safe</h2>
 *
 * <p>Not the consumed-message ledger, which only stops the same {@code eventId} twice. Two
 * cancellation events for one order are two different events, and what stops the second refunding
 * again is the idempotency key {@code RefundService} derives from the order, the payment and the
 * amount - so Stripe hands back the refund it already made. That is also why this handler does no
 * guarding of its own: a guard here would have to be keyed by the same things and would be a second
 * place for the rule to be slightly different.</p>
 *
 * <p>Exceptions are deliberately not caught. A refund that could not be made did not happen, and
 * saying so rolls the transaction back and retries the message - which is the behaviour wanted when
 * the alternative is an order cancelled with the buyer's money still taken.</p>
 *
 * <h2>This runs inside a transaction, which the Stripe handlers do not</h2>
 *
 * <p>common-amqp runs a handler in a transaction together with the duplicate-check marker, which is
 * what makes throwing roll the work back. That <strong>inverts</strong> the rule that holds
 * everywhere else in this service: a lazy association is safe to read here and throws in
 * {@code RefundService}, which runs outside a transaction because it calls Stripe. The same
 * expression is correct in one and broken in the other, so code is not moved between them without
 * checking what it reads.</p>
 *
 * <p>It also means a Stripe call made from here holds a database connection for the length of a
 * network round trip. Acceptable only because consumer concurrency bounds how many such calls there
 * can be at once, which is not true of an HTTP route.</p>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class OnOrderCancelled implements EventHandler<OrderCancelledEvent> {

    private final RefundService refunds;

    /**
     * The service that publishes this.
     *
     * <p>A literal, and it has to match that service's {@code confluo.amqp.service-name} exactly:
     * the queue this handler consumes is named from it and bound to that service's exchange, so a
     * mismatch is a service that starts cleanly and receives nothing.</p>
     *
     * @return {@code orders}
     */
    @Override
    public String producer() {
        return "orders";
    }

    @Override
    public void handle(EventEnvelope<OrderCancelledEvent> envelope) {
        OrderCancelledEvent event = envelope.payload();

        List<RefundResponse> issued = refunds.refundCancelledOrder(
                event.orderReferenceType(),
                event.orderReferenceId(),
                event.buyerFirebaseUid(),
                event.refundAmount(),
                event.reason());

        if (issued.isEmpty()) {
            log.info("Order {}:{} cancelled with no refund to make [event={}]",
                    event.orderReferenceType(), event.orderReferenceId(), envelope.eventId());
            return;
        }

        log.info("Order {}:{} cancelled; issued {} refund(s) totalling {} [event={}]",
                event.orderReferenceType(), event.orderReferenceId(), issued.size(),
                issued.stream().mapToLong(RefundResponse::amount).sum(), envelope.eventId());
    }
}
