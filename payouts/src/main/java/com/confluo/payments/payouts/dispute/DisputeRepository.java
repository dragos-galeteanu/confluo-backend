package com.confluo.payments.payouts.dispute;

import com.confluo.common.jpa.repository.BaseJpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;


/**
 * Disputes, by Stripe's id and by the payment they are against.
 */
@Repository
public interface DisputeRepository extends BaseJpaRepository<Dispute> {

    /**
     * The dispute an event is about.
     * @param stripeDisputeId Stripe's {@code dp_} identifier
     * @return the dispute, or empty
     */
    Optional<Dispute> findByStripeDisputeId(String stripeDisputeId);

    /**
     * Every dispute against a payment. More than one is possible, and a payment with two is worth
     * seeing both of.
     * @param paymentId the payment
     * @return its disputes
     */
    List<Dispute> findByPaymentId(UUID paymentId);
}
