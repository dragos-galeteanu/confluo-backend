package com.confluo.payments.payouts;

import com.confluo.common.jpa.entity.BaseJpaEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;


/**
 * One leg of the money's journey out.
 *
 * <p>Three movements that all look like "money left", and separating them is what makes a seller's
 * balance explainable. A {@link PayoutTransferKind#TRANSFER} moves a sale's share onto their Stripe
 * balance; a {@link PayoutTransferKind#PAYOUT} moves that balance to their bank, on Stripe's
 * schedule and usually days later; a {@link PayoutTransferKind#REVERSAL} takes a transfer back.</p>
 *
 * <p>This is what answers "when was I paid, and for what" - the question a seller asks most often,
 * and the one that cannot be answered from the sale alone.</p>
 */
@Getter
@Setter
@Entity
@Table(
        name = "payout_transfers",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_payout_transfers_stripe_object",
                columnNames = {"kind", "stripe_object_id"}))
public class PayoutTransfer extends BaseJpaEntity {

    /**
     * Whose money it is - the connected account's id, not the account.
     *
     * <p>{@code ConnectedAccount} belongs to onboarding and this module reads nothing from it but
     * this id, so there is nothing for an association to buy. The derived finders in
     * {@link PayoutTransferRepository} work on the column directly.</p>
     */
    @Column(name = "connected_account_id", nullable = false)
    private UUID connectedAccountId;

    /** which of the three movements this is */
    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 16)
    private PayoutTransferKind kind;

    /**
     * Stripe's id for whichever of the three objects this is. Unique per kind rather than overall:
     * they are different object types in different namespaces, and nothing guarantees the strings
     * will not collide.
     */
    @Column(name = "stripe_object_id", nullable = false, length = 255)
    private String stripeObjectId;

    /**
     * Which sale this is the seller's share of, where that is known. Null on a payout, which
     * aggregates whatever was on their balance and corresponds to no single sale.
     *
     * <p>The payment's id, for the same reason as above: {@code Payment} belongs to marketplace,
     * and a clawback asks "which transfers came from this sale", which is a question about this
     * column.</p>
     */
    @Column(name = "source_payment_id")
    private UUID sourcePaymentId;

    /** how much moved, in minor units */
    @Column(name = "amount", nullable = false)
    private long amount;

    /** uppercase ISO 4217 */
    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    /**
     * Stripe's status, free text, and null for a transfer: a transfer has no status because it
     * either happened or the API call failed.
     */
    @Column(name = "status", length = 32)
    private String status;

    /** when the money is expected in the bank, which is not when the payout was created */
    @Column(name = "arrival_date")
    private Instant arrivalDate;

    /** Stripe's code for a payout the bank refused */
    @Column(name = "failure_code", length = 64)
    private String failureCode;

    /** Stripe's wording of it */
    @Column(name = "failure_message", columnDefinition = "TEXT")
    private String failureMessage;
}
