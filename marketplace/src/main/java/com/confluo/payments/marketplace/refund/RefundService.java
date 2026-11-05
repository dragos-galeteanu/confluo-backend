package com.confluo.payments.marketplace.refund;

import com.confluo.common.exception.ResourceNotFoundException;
import com.confluo.payments.marketplace.Payment;
import com.confluo.payments.marketplace.PaymentRepository;
import com.confluo.payments.marketplace.PaymentStatus;
import com.confluo.payments.marketplace.refund.dto.RefundCreateRequest;
import com.confluo.payments.marketplace.refund.dto.RefundReason;
import com.confluo.payments.marketplace.refund.dto.RefundResponse;
import com.confluo.stripe.RefundGateway;
import com.stripe.param.RefundCreateParams;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;


/**
 * Sends money back.
 *
 * <h2>Two ways in, one way through</h2>
 *
 * <p>Support, through this service's own API; or the service that owns orders, by cancelling one.
 * Both arrive at {@link #issue}, which is the only place that decides anything about money; what
 * differs is who is allowed to ask, what the idempotency key is derived from, and what gets written
 * down about why.</p>
 *
 * <p>A payer cannot ask, deliberately - deciding whether they may undo a payment needs to know
 * whether the thing bought has been delivered, and this service holds an order reference it never
 * resolves. So that rule lives in the service that owns orders, and its cancellation reaches here as
 * {@code order.cancelled}. {@code RefundAuthorizationPolicy} has the reasoning;
 * {@link RefundInitiator#USER_REQUEST} is consequently a value nothing produces.</p>
 *
 * <h2>Why this writes a row when the webhook is supposed to own them</h2>
 *
 * <p>Everywhere else in this service, mirrored state is written by the webhook and by nothing else.
 * Refunds are the exception, and deliberately: {@link RefundInitiator} is not something a webhook
 * can know. Stripe's refund object does not say who asked for it, so a refund this service issued
 * and did not record would be created by {@code RefundMirroringHandler} as
 * {@link RefundInitiator#STRIPE_DASHBOARD} - which would make the provenance column, the only
 * reason it exists, wrong for every refund that went through here.</p>
 *
 * <p>So the row is written <strong>after</strong> Stripe, not before: {@code stripe_refund_id} is
 * {@code NOT NULL} and the id is Stripe's to mint. That is the same order as a subscription
 * checkout and the opposite of an order checkout, and the reason is the same each time - whoever
 * owns the identifier goes first.</p>
 *
 * <h2>What makes a retry safe</h2>
 *
 * <p>The idempotency key, and only the key. Every path derives one from what it is refunding rather
 * than from the request that asked, so a double tap, a timed-out response and a redelivered
 * cancellation event all resolve to the one refund Stripe already made. A refund of "everything
 * left" sends <strong>no</strong> amount at all for exactly this reason: Stripe defaults to the
 * remainder, which keeps the request identical even when the remainder has since changed - and an
 * idempotency key reused with different parameters is an error rather than a replay.</p>
 *
 * <p>Not {@code @Transactional}, like the other services here: a transaction spanning the Stripe
 * call would hold a database connection across a network round trip. Which used to mean fetching
 * a payment's connected account explicitly, because reading it afterwards was a lazy proxy with no
 * session. It no longer does: {@link PaymentRepository} returns rows that carry their own seller,
 * so there is no association here to initialise or forget.</p>
 *
 * <h2>What this does not do</h2>
 *
 * <p>It publishes nothing. {@code refund.issued} is announced by the webhook when the refund first
 * reaches succeeded, because that is when the money is actually on its way back - a refund is
 * submitted to the payer's bank and can still fail days later. Announcing here would tell somebody
 * their money was returned at the moment it was requested.</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RefundService {

    private final PaymentRepository payments;
    private final RefundRepository refunds;
    private final RefundGateway gateway;
    private final RefundProperties properties;

    /**
     * Refunds one payment, because a person asked.
     *
     * @param request what to refund and why
     * @param initiator who asked, as the authorization policy determined - {@code SUPPORT} for
     *                  every caller this route currently admits. Passed in rather than worked out
     *                  here, because it is the outcome of an authorization decision and this
     *                  service has no view of the caller
     * @return the refund as recorded
     * @throws ResourceNotFoundException if there is no such payment
     * @throws IllegalStateException if the payment was never paid, has nothing left to refund, or
     *         cannot be refunded for more than remains
     */
    public RefundResponse refund(RefundCreateRequest request, RefundInitiator initiator) {
        Payment payment = payments.findById(request.paymentId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "No payment %s".formatted(request.paymentId())));

        String idempotencyKey = request.idempotencyKey() != null && !request.idempotencyKey().isBlank()
                ? request.idempotencyKey()
                : "refund:payment:%s:%s".formatted(payment.getId(), amountKeyPart(request.amount()));

        Refund refund = issue(
                payment,
                request.amount(),
                initiator,
                stripeReasonOf(request.reason()),
                request.notes(),
                idempotencyKey);

        log.info("Refund issued by {} [refund={}, payment={}, amount={} {}]",
                initiator, refund.getId(), payment.getId(), refund.getAmount(), refund.getCurrency());
        return response(refund, payment);
    }

    /**
     * Refunds whatever was paid for an order that has been cancelled.
     *
     * <p>Takes the cancellation's parts rather than the event, so that nothing in this service's
     * own logic depends on a contract belonging to a service that does not exist yet.</p>
     *
     * <p><strong>Nothing to refund is the normal outcome.</strong> Most cancelled orders were never
     * paid for, and an order whose payment has already been fully refunded is cancelled again
     * without consequence. Both return an empty list rather than failing, because failing here
     * parks a message and asks somebody to look at a cancellation that needed nothing.</p>
     *
     * <p>A reference with <strong>several</strong> live payments is possible - a first attempt that
     * failed and a second that succeeded are both payments for one order, and only the succeeded
     * ones are candidates. Where more than one still has money in it and the cancellation names an
     * amount, this refuses: splitting a stated amount across payments is a guess about money, and
     * guessing is worse than parking the message for somebody to read.</p>
     *
     * @param orderReferenceType what kind of thing was ordered
     * @param orderReferenceId its id in the service that owns orders
     * @param buyerFirebaseUid who ordered it, used only to notice that the payment found belongs
     *                         to somebody else - which is logged rather than refused, because the
     *                         reference pair is what identifies the payment and a mismatch is a bug
     *                         to investigate rather than a reason to leave a cancelled order
     *                         unrefunded. May be null
     * @param amount how much to send back in minor units, or null for everything left
     * @param reason why it was cancelled, recorded on the refund
     * @return the refunds issued, which may be empty
     * @throws IllegalStateException if the amount cannot be attributed to one payment
     */
    public List<RefundResponse> refundCancelledOrder(
            String orderReferenceType,
            String orderReferenceId,
            String buyerFirebaseUid,
            Long amount,
            String reason) {

        List<Payment> candidates = payments
                .findByOrderReferenceTypeAndOrderReferenceId(orderReferenceType, orderReferenceId)
                .stream()
                .filter(payment -> payment.getStatus() == PaymentStatus.SUCCEEDED)
                .filter(payment -> payment.getAmount() > payment.getAmountRefunded())
                .toList();

        if (candidates.isEmpty()) {
            log.info("Order {}:{} was cancelled with nothing to refund",
                    orderReferenceType, orderReferenceId);
            return List.of();
        }

        if (candidates.size() > 1 && amount != null) {
            throw new IllegalStateException(
                    ("Order %s:%s asks for %d back but has %d refundable payments; which one it "
                            + "comes off cannot be guessed")
                            .formatted(orderReferenceType, orderReferenceId, amount, candidates.size()));
        }

        List<RefundResponse> issued = new ArrayList<>();
        for (Payment payment : candidates) {
            if (buyerFirebaseUid != null && !buyerFirebaseUid.equals(payment.getFirebaseUid())) {
                log.warn("Order {}:{} says it belongs to {} but payment {} was made by {}; "
                                + "refunding the payment anyway",
                        orderReferenceType, orderReferenceId, buyerFirebaseUid,
                        payment.getId(), payment.getFirebaseUid());
            }

            Refund refund = issue(
                    payment,
                    amount,
                    RefundInitiator.ORDER_EVENT,
                    RefundCreateParams.Reason.REQUESTED_BY_CUSTOMER,
                    reason == null || reason.isBlank()
                            ? "Order cancelled"
                            : "Order cancelled: " + reason,
                    "refund:order-cancelled:%s:%s:%s:%s".formatted(
                            orderReferenceType, orderReferenceId, payment.getId(),
                            amountKeyPart(amount)));

            log.info("Refunded cancelled order {}:{} [refund={}, payment={}, amount={} {}]",
                    orderReferenceType, orderReferenceId, refund.getId(), payment.getId(),
                    refund.getAmount(), refund.getCurrency());
            issued.add(response(refund, payment));
        }
        return issued;
    }

    /**
     * Every refund against one payment, newest first.
     *
     * <p>The payment is loaded once and shared, rather than each refund reaching back through its
     * own {@code payment} association: that association is lazy and this method is not in a
     * transaction, so walking it would throw - and if it did not, it would be one query per
     * refund for a value every row in the list shares.</p>
     *
     * @param paymentId the payment
     * @return its refunds
     * @throws ResourceNotFoundException if there is no such payment
     */
    public List<RefundResponse> listFor(UUID paymentId) {
        Payment payment = payments.findById(paymentId)
                .orElseThrow(() -> new ResourceNotFoundException("No payment %s".formatted(paymentId)));

        return refunds.findByPaymentIdOrderByCreatedAtDesc(paymentId).stream()
                .map(refund -> response(refund, payment))
                .toList();
    }

    /**
     * The one place a refund actually happens.
     *
     * @param payment what is being refunded, with its connected account already loaded
     * @param requested how much, or null for everything left
     * @param initiator who asked
     * @param stripeReason Stripe's own reason code, which feeds their fraud and dispute analytics
     *                     rather than anything here
     * @param notes why, in the words of whoever asked
     * @param idempotencyKey what makes a retry a no-op
     * @return the refund as recorded
     */
    private Refund issue(
            Payment payment,
            Long requested,
            RefundInitiator initiator,
            RefundCreateParams.Reason stripeReason,
            String notes,
            String idempotencyKey) {

        if (payment.getStatus() != PaymentStatus.SUCCEEDED) {
            throw new IllegalStateException(
                    "Payment %s is %s and so has nothing to refund"
                            .formatted(payment.getId(), payment.getStatus()));
        }

        // The remainder as this service last heard it. Stripe is the real cap - it refuses a refund
        // beyond the charge - and this check exists to refuse the obvious cases here, with a
        // message naming the payment, rather than passing them on to be refused in Stripe's words.
        // It can be stale by design: amount_refunded is moved by charge.refunded, which arrives
        // after the refund that caused it, so two refunds issued seconds apart both see the older
        // total. The idempotency key is what makes that harmless for a retry, and Stripe's own cap
        // is what makes it harmless for everything else.
        long remaining = payment.getAmount() - payment.getAmountRefunded();
        if (remaining <= 0) {
            throw new IllegalStateException(
                    "Payment %s has already been refunded in full (%d of %d)"
                            .formatted(payment.getId(), payment.getAmountRefunded(), payment.getAmount()));
        }
        if (requested != null && requested <= 0) {
            throw new IllegalArgumentException(
                    "A refund of %d is not a refund".formatted(requested));
        }
        if (requested != null && requested > remaining) {
            throw new IllegalStateException(
                    "Payment %s has %d left to refund and cannot give back %d"
                            .formatted(payment.getId(), remaining, requested));
        }

        RefundGateway.IssuedRefund stripeRefund =
                gateway.create(params(payment, requested, stripeReason), idempotencyKey);
        return record(payment, stripeRefund, initiator, notes);
    }

    /**
     * What to ask Stripe for.
     *
     * <p>Every line is a decision:</p>
     *
     * <ul>
     *   <li>the amount is <strong>omitted</strong> for a full refund rather than computed, because
     *       Stripe already means "the remainder" by its absence and an omitted amount keeps a
     *       retried request byte-identical to the first;</li>
     *   <li>the payment intent is preferred over the charge because that is the identifier this
     *       service holds for almost every payment; the charge is the fallback for a payment
     *       recorded from a transfer, which names one and never an intent;</li>
     *   <li>{@code reverseTransfer} and {@code refundApplicationFee} are only sent when there is a
     *       connected account to reverse <em>to</em>. Sent on a charge with no transfer - a
     *       subscription invoice paid to the platform - Stripe refuses the refund outright;</li>
     *   <li>and the application fee is only mentioned when the transfer is being reversed, because
     *       Stripe will not take a fee out of a transfer that is staying where it is.</li>
     * </ul>
     *
     * @param payment what is being refunded
     * @param requested how much, or null for the remainder
     * @param reason Stripe's reason code
     * @return the parameters
     */
    private RefundCreateParams params(Payment payment, Long requested, RefundCreateParams.Reason reason) {
        RefundCreateParams.Builder builder = RefundCreateParams.builder().setReason(reason);

        if (requested != null) {
            builder.setAmount(requested);
        }

        if (payment.getStripePaymentIntentId() != null) {
            builder.setPaymentIntent(payment.getStripePaymentIntentId());
        } else if (payment.getStripeChargeId() != null) {
            builder.setCharge(payment.getStripeChargeId());
        } else {
            throw new IllegalStateException(
                    ("Payment %s is recorded as %s but names neither a Stripe payment intent nor a "
                            + "charge, so there is nothing to refund")
                            .formatted(payment.getId(), payment.getStatus()));
        }

        if (payment.getConnectedAccountId() != null) {
            boolean reverseTransfer = properties.reverseTransfer();
            builder.setReverseTransfer(reverseTransfer);
            if (reverseTransfer) {
                builder.setRefundApplicationFee(properties.refundApplicationFee());
            }
        }

        builder.putMetadata("payment_id", payment.getId().toString());
        if (payment.getOrderReferenceType() != null) {
            builder.putMetadata("order_reference_type", payment.getOrderReferenceType());
        }
        if (payment.getOrderReferenceId() != null) {
            builder.putMetadata("order_reference_id", payment.getOrderReferenceId());
        }
        return builder.build();
    }

    /**
     * Writes down the refund Stripe made.
     *
     * <p>Finds the row first, because the webhook may already have written it: Stripe can deliver
     * {@code charge.refunded} before the create call's response has even been read here, and
     * {@code uq_refunds_stripe_id} would then refuse an insert. Where that has happened the row
     * exists as {@link RefundInitiator#STRIPE_DASHBOARD}, and correcting it is the one case where
     * overwriting that column is right - this is the code that knows who asked, which is precisely
     * what the webhook did not.</p>
     *
     * <p>The status is only written for a row that is new or still pending. A refund the webhook
     * has already moved to succeeded or failed must not be dragged back to the {@code pending}
     * the create call reported, which is a staleness guard in the same spirit as the handlers' -
     * what differs is that there is no event timestamp to compare, so the terminal states stand in
     * for one.</p>
     *
     * <p>{@code lastStripeEventAt} is deliberately left alone. It records the newest <em>event</em>
     * applied to the row, and nothing here is an event; stamping it with this moment would make the
     * webhook that follows look stale and be ignored.</p>
     *
     * @param payment what was refunded
     * @param stripeRefund what Stripe created
     * @param initiator who asked
     * @param notes why
     * @return the saved row
     */
    private Refund record(
            Payment payment,
            RefundGateway.IssuedRefund stripeRefund,
            RefundInitiator initiator,
            String notes) {

        Optional<Refund> existing = refunds.findByStripeRefundId(stripeRefund.id());
        Refund refund = existing.orElseGet(Refund::new);

        if (existing.isEmpty()) {
            refund.setPayment(payment);
            refund.setStripeRefundId(stripeRefund.id());
        } else {
            log.info("Refund {} was already recorded; correcting its provenance from {} to {}",
                    stripeRefund.id(), refund.getInitiatedBy(), initiator);
        }

        refund.setInitiatedBy(initiator);
        refund.setAmount(stripeRefund.amount());
        if (stripeRefund.currency() != null) {
            // Stripe speaks lowercase; the column is constrained to upper, and this is the boundary.
            refund.setCurrency(stripeRefund.currency().toUpperCase(Locale.ROOT));
        }
        refund.setReason(stripeRefund.reason());
        if (notes != null && !notes.isBlank()) {
            refund.setNotes(notes);
        }
        if (existing.isEmpty() || refund.getStatus() == RefundStatus.PENDING) {
            refund.setStatus(RefundStatus.of(stripeRefund.status()));
        }

        return refunds.save(refund);
    }

    /**
     * This service's reason code as Stripe's.
     *
     * <p>A switch over this service's own enum rather than a name lookup, so that adding a value to
     * {@link RefundReason} is a compile error here instead of a request Stripe rejects at
     * runtime - which is the entire reason that enum exists rather than Stripe's.</p>
     *
     * @param reason what the caller said, or null
     * @return Stripe's code, defaulting to a customer request
     */
    private RefundCreateParams.Reason stripeReasonOf(RefundReason reason) {
        if (reason == null) {
            return RefundCreateParams.Reason.REQUESTED_BY_CUSTOMER;
        }
        return switch (reason) {
            case DUPLICATE -> RefundCreateParams.Reason.DUPLICATE;
            case FRAUDULENT -> RefundCreateParams.Reason.FRAUDULENT;
            case REQUESTED_BY_CUSTOMER -> RefundCreateParams.Reason.REQUESTED_BY_CUSTOMER;
        };
    }

    /**
     * The part of an idempotency key that says how much.
     *
     * <p>A word rather than the remainder for a full refund, so that the key does not change when
     * the remainder does - which is the whole reason the amount is omitted from the request as
     * well.</p>
     *
     * @param amount the requested amount, or null
     * @return the key fragment
     */
    private String amountKeyPart(Long amount) {
        return amount == null ? "remaining" : amount.toString();
    }

    private RefundResponse response(Refund refund, Payment payment) {
        return new RefundResponse(
                refund.getId(),
                payment.getId(),
                refund.getStripeRefundId(),
                refund.getAmount(),
                refund.getCurrency(),
                refund.getStatus(),
                refund.getInitiatedBy(),
                refund.getReason(),
                refund.getNotes(),
                refund.getCreatedAt());
    }
}
