package com.confluo.payments.payouts;

import com.confluo.common.jpa.repository.BaseJpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;


/**
 * Money leaving, in its three forms.
 */
@Repository
public interface PayoutTransferRepository extends BaseJpaRepository<PayoutTransfer> {

    /**
     * The row a Stripe object corresponds to.
     *
     * <p>Both arguments, because the uniqueness is per kind: the three are different object types
     * in different namespaces, and looking one up by id alone could find another kind's row.</p>
     *
     * @param kind which movement it is
     * @param stripeObjectId Stripe's id for that object
     * @return the row, or empty
     */
    Optional<PayoutTransfer> findByKindAndStripeObjectId(PayoutTransferKind kind, String stripeObjectId);

    /**
     * The movements of one kind recorded against one sale.
     *
     * <p>How a lost dispute finds the transfer it has to claw back: the dispute names a payment, and
     * the {@link PayoutTransferKind#TRANSFER} row against that payment holds the Stripe transfer id
     * the reversal needs.</p>
     *
     * <p>A list rather than one, because nothing in the schema says there is only one. Normally
     * there is - a destination charge makes exactly one transfer - but a transfer made by hand
     * against the same sale would be a second, and a caller that has to claw money back should see
     * that rather than be handed whichever row came first.</p>
     *
     * @param kind which movement to look for
     * @param sourcePaymentId the sale
     * @return the matching movements, newest first
     */
    List<PayoutTransfer> findByKindAndSourcePaymentIdOrderByCreatedAtDesc(
            PayoutTransferKind kind, UUID sourcePaymentId);

    /**
     * One seller's movements, newest first. What answers "when was I paid, and for what".
     * @param connectedAccountId the connected account's id in this service
     * @return their transfers, payouts and reversals
     */
    List<PayoutTransfer> findByConnectedAccountIdOrderByCreatedAtDesc(UUID connectedAccountId);
}
