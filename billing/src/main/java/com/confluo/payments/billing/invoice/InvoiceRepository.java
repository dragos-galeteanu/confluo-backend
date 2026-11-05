package com.confluo.payments.billing.invoice;

import com.confluo.common.jpa.repository.BaseJpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;


/**
 * Invoices: the billing history, and what a receipt is rendered from.
 */
@Repository
public interface InvoiceRepository extends BaseJpaRepository<Invoice> {

    /**
     * The invoice an event is about.
     * @param stripeInvoiceId Stripe's {@code in_} identifier
     * @return the invoice, or empty
     */
    Optional<Invoice> findByStripeInvoiceId(String stripeInvoiceId);

    /**
     * A subscription's cycles, newest period first.
     * @param subscriptionId the subscription
     * @return its invoices
     */
    List<Invoice> findBySubscriptionIdOrderByPeriodStartDesc(UUID subscriptionId);

    /**
     * Somebody's whole billing history, newest first.
     * @param firebaseUid the uid
     * @return their invoices
     */
    List<Invoice> findByFirebaseUidOrderByCreatedAtDesc(String firebaseUid);
}
