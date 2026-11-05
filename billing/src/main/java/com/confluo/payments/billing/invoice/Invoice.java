package com.confluo.payments.billing.invoice;

import com.confluo.common.jpa.entity.BaseJpaEntity;
import com.confluo.payments.billing.subscription.Subscription;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;


/**
 * What was billed, for one period. The queryable billing history, and what a receipt is rendered
 * from.
 *
 * <p>{@link #subscription} is nullable because not every invoice belongs to one: a proration
 * charged immediately on an upgrade is its own invoice, and so is anything billed out of band.</p>
 *
 * <p>{@link #hostedInvoiceUrl} and {@link #invoicePdf} are kept as Stripe's URLs rather than
 * fetched and stored. They are generated, they stay valid, and a PDF in this database is a PDF to
 * keep in step with a corrected invoice.</p>
 *
 * <p>{@link #attemptCount} and {@link #nextPaymentAttempt} are both Stripe's, and both are what a
 * dunning message is worded from - "we will try again on Tuesday" is only true if it comes from the
 * side doing the trying.</p>
 */
@Getter
@Setter
@Entity
@Table(
        name = "invoices",
        uniqueConstraints = @UniqueConstraint(name = "uq_invoices_stripe_id", columnNames = "stripe_invoice_id"))
public class Invoice extends BaseJpaEntity {

    /** Stripe's own {@code in_} identifier */
    @Column(name = "stripe_invoice_id", nullable = false, length = 255)
    private String stripeInvoiceId;

    /** the subscription this is a cycle of, or null for a one-off invoice */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "subscription_id")
    private Subscription subscription;

    /** who owes it */
    @Column(name = "firebase_uid", nullable = false, length = 128)
    private String firebaseUid;

    /**
     * Stripe's human-readable number, which is what somebody quotes when they ask about a charge.
     * Null on a draft: Stripe assigns it at finalisation.
     */
    @Column(name = "number", length = 64)
    private String number;

    /** where it has got to */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private InvoiceStatus status;

    /** what was owed, in minor units */
    @Column(name = "amount_due", nullable = false)
    private long amountDue = 0;

    /** what has been collected, which is less than the above until it is paid */
    @Column(name = "amount_paid", nullable = false)
    private long amountPaid = 0;

    /** what of it was tax, which Stripe Tax calculated and which is not revenue */
    @Column(name = "tax_amount", nullable = false)
    private long taxAmount = 0;

    /** uppercase ISO 4217 */
    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    /** the start of the period billed */
    @Column(name = "period_start")
    private Instant periodStart;

    /** the end of it */
    @Column(name = "period_end")
    private Instant periodEnd;

    /** Stripe's hosted copy, which is the one a person is sent to */
    @Column(name = "hosted_invoice_url", columnDefinition = "TEXT")
    private String hostedInvoiceUrl;

    /** Stripe's generated PDF */
    @Column(name = "invoice_pdf", columnDefinition = "TEXT")
    private String invoicePdf;

    /** how many times Stripe has tried to collect */
    @Column(name = "attempt_count", nullable = false)
    private int attemptCount = 0;

    /** when it will next try, which is the only honest thing to tell somebody who has lapsed */
    @Column(name = "next_payment_attempt")
    private Instant nextPaymentAttempt;

    /** the timestamp of the most recent Stripe event applied to this row */
    @Column(name = "last_stripe_event_at")
    private Instant lastStripeEventAt;
}
