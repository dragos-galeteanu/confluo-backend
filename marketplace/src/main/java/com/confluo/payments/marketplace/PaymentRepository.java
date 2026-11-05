package com.confluo.payments.marketplace;

import com.confluo.common.jpa.repository.BaseJpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;


/**
 * Payments, found by whichever identifier the caller happens to hold.
 *
 * <p>Four of these exist because four different callers arrive with four different names for the
 * same row: a webhook has Stripe's intent or session id, a retried create has the idempotency key,
 * and the orders service has only its own reference. None of them can be converted into another
 * without this table.</p>
 */
@Repository
public interface PaymentRepository extends BaseJpaRepository<Payment> {

    /**
     * The payment a Stripe intent belongs to, which is how most webhooks find their row.
     * @param stripePaymentIntentId Stripe's {@code pi_} identifier
     * @return the payment, or empty
     */
    Optional<Payment> findByStripePaymentIntentId(String stripePaymentIntentId);

    /**
     * The payment a Checkout session belongs to.
     *
     * <p>Needed as well as the above, and not instead of it:
     * {@code checkout.session.completed} names the session, while every later event names the
     * intent, and the intent does not exist until the payer reaches the hosted page.</p>
     *
     * @param stripeCheckoutSessionId Stripe's {@code cs_} identifier
     * @return the payment, or empty
     */
    Optional<Payment> findByStripeCheckoutSessionId(String stripeCheckoutSessionId);

    /**
     * The payment a Stripe charge belongs to.
     *
     * <p>How a transfer finds the sale it funded. Stripe's {@code Transfer} names a charge and
     * never an intent, so this lookup is the only one that can answer it.</p>
     *
     * @param stripeChargeId Stripe's {@code ch_} identifier
     * @return the payment, or empty
     */
    Optional<Payment> findByStripeChargeId(String stripeChargeId);

    /**
     * The payment a previous create already made.
     *
     * <p>What turns a retry into a no-op. A caller's double tap, a timed-out request or a
     * redelivered order event all arrive with the same key, and finding the row is the difference
     * between returning the existing session and charging somebody twice.</p>
     *
     * @param idempotencyKey the key derived from the caller's own identifiers
     * @return the payment, or empty if this is genuinely the first attempt
     */
    Optional<Payment> findByIdempotencyKey(String idempotencyKey);

    /**
     * The payments made for one thing in another service.
     *
     * <p>A list rather than one: a first attempt that failed and a second that succeeded are both
     * payments for the same order, and collapsing them would hide the first.</p>
     *
     * @param orderReferenceType what kind of thing was bought
     * @param orderReferenceId its id in the service that owns it
     * @return every payment against that reference
     */
    List<Payment> findByOrderReferenceTypeAndOrderReferenceId(
            String orderReferenceType, String orderReferenceId);

    /**
     * Whether somebody has ever paid.
     *
     * <p>What a first-purchase-only discount is checked against. Stripe enforces the same thing on
     * a promotion code through {@code restrictions.first_time_transaction}, and this is the local
     * half - which is what lets the refusal happen before the payer is sent to Stripe, rather than
     * on the page in front of them.</p>
     *
     * <p>{@code exists} rather than a count: the question is whether there is one, and counting
     * every payment somebody has ever made to answer it reads the whole history.</p>
     *
     * @param firebaseUid whose history it is
     * @param status the status that counts, which is {@link PaymentStatus#SUCCEEDED}
     * @return true if they have at least one payment in that state
     */
    boolean existsByFirebaseUidAndStatus(String firebaseUid, PaymentStatus status);

    /**
     * What one seller has been paid for, newest first.
     *
     * <p>A plain derived query over the {@code connected_account_id} column, which is all it ever
     * was: {@code connectedAccountId} is a {@link UUID} on the row and not an association, so
     * there is no join here and nothing to fetch.</p>
     *
     * <p>Two fetch-joining finders used to live here, both of them loading a payment's connected
     * account for code that runs outside a transaction. They are gone with the association:
     * {@code sellerFirebaseUid} is on the payment itself, so the plain finders above answer those
     * callers.</p>
     *
     * @param connectedAccountId the connected account's id in this service
     * @return their payments
     */
    List<Payment> findByConnectedAccountIdOrderByCreatedAtDesc(UUID connectedAccountId);
}
