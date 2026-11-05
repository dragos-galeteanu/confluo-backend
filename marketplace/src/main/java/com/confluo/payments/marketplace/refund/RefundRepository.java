package com.confluo.payments.marketplace.refund;

import com.confluo.common.jpa.repository.BaseJpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;


/**
 * Refunds, by Stripe's id and by the payment they came off.
 */
@Repository
public interface RefundRepository extends BaseJpaRepository<Refund> {

    /**
     * The refund an event is about.
     * @param stripeRefundId Stripe's {@code re_} identifier
     * @return the refund, or empty
     */
    Optional<Refund> findByStripeRefundId(String stripeRefundId);

    /**
     * Every refund against a payment, which is what explains its refunded total.
     * @param paymentId the payment
     * @return its refunds, newest first
     */
    List<Refund> findByPaymentIdOrderByCreatedAtDesc(UUID paymentId);

    /**
     * Whether a payment has ever been refunded by one kind of asker.
     *
     * <p>A question about provenance, not a guard - which is a correction of what this was written
     * for. Blocking a second automatic refund outright would also block the legitimate one: a
     * partial cancellation followed by the rest of the order being cancelled is two refunds against
     * one payment, both {@code ORDER_EVENT}. What actually makes a redelivered cancellation harmless
     * is the idempotency key {@code RefundService} derives from the order, the payment and the
     * amount, which makes Stripe hand back the refund it already made.</p>
     *
     * @param paymentId the payment
     * @param initiatedBy who asked
     * @return true if such a refund exists
     */
    boolean existsByPaymentIdAndInitiatedBy(UUID paymentId, RefundInitiator initiatedBy);
}
