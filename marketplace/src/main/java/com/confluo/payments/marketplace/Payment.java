package com.confluo.payments.marketplace;

import com.confluo.common.jpa.entity.BaseJpaEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;


/**
 * One payment that should exist, and the state it has got to.
 *
 * <p>Written <strong>before</strong> the payer is sent to Stripe, in the transaction that decided
 * to charge. That is the same shape as the outbox and the notifications service's own queue, and
 * holds for the same reason: a webhook arriving a second later always has something to attach to,
 * whereas a row written afterwards is a payment that can succeed with nothing recording it.</p>
 *
 * <p>Under destination charges the platform is the merchant of record. So a marketplace sale is one
 * charge on the platform's account with {@link #connectedAccountId} as the transfer destination and
 * {@link #applicationFeeAmount} as the platform's cut - not two payments, and not a charge on the
 * seller's account. It also means the platform owns the dispute, which is what
 * {@code Dispute} is about - named rather than linked, so that a sale does not depend on the
 * package that records money being taken back off one.</p>
 *
 * <p>{@link #orderReferenceType} and {@link #orderReferenceId} are what was bought, as a reference
 * this service does not interpret. The service that owns orders does not exist yet; when it does,
 * this is the only coupling between the two, and it is deliberately an opaque pair rather than a
 * foreign key into a database that is not this one.</p>
 */
@Getter
@Setter
@Entity
@Table(name = "payments")
public class Payment extends BaseJpaEntity {

    /**
     * Stripe's {@code pi_} identifier, or null until Stripe creates the intent - which happens when
     * the payer reaches the hosted page, so a session abandoned at the redirect never gets one.
     */
    @Column(name = "stripe_payment_intent_id", length = 255)
    private String stripePaymentIntentId;

    /** Stripe's {@code cs_} identifier for the Checkout session this came from */
    @Column(name = "stripe_checkout_session_id", length = 255)
    private String stripeCheckoutSessionId;

    /**
     * Stripe's {@code ch_} identifier, which is not the intent: the intent is the attempt and the
     * charge is the one that worked.
     *
     * <p>Kept for one reason, and it is not redundancy. A {@code Transfer} names its source as a
     * <strong>charge</strong> and offers no payment intent, so this is the only route from a
     * seller's payout back to the sale it came from - which is the question a seller asks most
     * often.</p>
     */
    @Column(name = "stripe_charge_id", length = 255)
    private String stripeChargeId;

    /** who is paying */
    @Column(name = "firebase_uid", nullable = false, length = 128)
    private String firebaseUid;

    /**
     * Who the money is transferred on to, or null for a payment to the platform itself such as a
     * subscription invoice.
     *
     * <p>The account's <strong>id</strong> and not the account. {@code ConnectedAccount} belongs to
     * onboarding, which is a module today and a service later, and a {@code @ManyToOne} across that
     * line is a join this service will not be able to make. It was also the association that threw
     * most often: the services here call Stripe outside a transaction, where a lazy proxy has no
     * session - which is why {@link #sellerFirebaseUid} sits on this row rather than being read
     * through it.</p>
     */
    @Column(name = "connected_account_id")
    private UUID connectedAccountId;

    /**
     * Whose sale it is, copied from the connected account when this row was written.
     *
     * <p>Null exactly when {@link #connectedAccountId} is, which
     * {@code ck_payments_seller_mirrors_account} enforces rather than trusts. This one field is
     * everything the code that used to walk the association wanted: the handler that announces a
     * sale, the dispute that names a seller, the clawback that logs whose money moved.</p>
     */
    @Column(name = "seller_firebase_uid", length = 128)
    private String sellerFirebaseUid;

    /**
     * The {@code acct_} the money was transferred to, copied at the same moment.
     *
     * <p>Nothing reads it yet, and it is still not spare. Under destination charges this is where
     * the money actually went, and a seller who later connects a different Stripe account would
     * otherwise make that unanswerable from this row - a reconciliation question about a charge
     * has to be answered by the charge, not by whatever the seller's account happens to be
     * today.</p>
     */
    @Column(name = "seller_stripe_account_id", length = 255)
    private String sellerStripeAccountId;

    /** what kind of thing was bought, as the service that owns orders names it */
    @Column(name = "order_reference_type", length = 64)
    private String orderReferenceType;

    /** its id in that service, which this one never resolves */
    @Column(name = "order_reference_id", length = 128)
    private String orderReferenceId;

    /** the total charged, in the currency's minor units */
    @Column(name = "amount", nullable = false)
    private long amount;

    /** uppercase ISO 4217, folded from the lowercase Stripe speaks */
    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    /** the platform's cut, or null for a payment with nobody to take a cut from */
    @Column(name = "application_fee_amount")
    private Long applicationFeeAmount;

    /**
     * What Stripe Tax calculated and collected, included in {@link #amount}. Stored because an
     * invoice has to state it and because it is not the platform's revenue.
     */
    @Column(name = "tax_amount")
    private Long taxAmount;

    /** how much has been sent back, which a partial refund moves and a constraint caps at the total */
    @Column(name = "amount_refunded", nullable = false)
    private long amountRefunded = 0;

    /** where it has got to */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 24)
    private PaymentStatus status;

    /** when the money arrived, and so the only moment anything may be delivered from */
    @Column(name = "captured_at")
    private Instant capturedAt;

    /** Stripe's code for a declined or failed charge */
    @Column(name = "failure_code", length = 64)
    private String failureCode;

    /** Stripe's wording of it, for a person reading the log */
    @Column(name = "failure_message", columnDefinition = "TEXT")
    private String failureMessage;

    /**
     * The key this service sent to Stripe when it created the session. Unique, which is what makes
     * a retried create - a caller's double tap, a timeout, a redelivered order event - resolve to
     * this row instead of charging somebody twice.
     */
    @Column(name = "idempotency_key", length = 255)
    private String idempotencyKey;

    /**
     * The timestamp of the most recent Stripe event applied to this row. Webhooks have no ordering
     * guarantee, so a handler compares against this and ignores anything older.
     */
    @Column(name = "last_stripe_event_at")
    private Instant lastStripeEventAt;
}
