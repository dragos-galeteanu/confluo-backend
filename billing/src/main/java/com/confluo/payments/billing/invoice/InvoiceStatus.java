package com.confluo.payments.billing.invoice;

/**
 * Where an invoice has got to, mirroring Stripe's own five states.
 *
 * <p>{@link #PAID} is what a receipt may be sent for, and {@link #UNCOLLECTIBLE} is the accounting
 * term for written off - Stripe does not delete an invoice it could not collect, and neither should
 * this. {@link #VOID} is the one to be careful with: it means the invoice should never have existed,
 * which is different from one that existed and was refunded.</p>
 */
public enum InvoiceStatus {

    /** not finalised, so not owed by anybody yet */
    DRAFT,

    /** finalised and awaiting payment */
    OPEN,

    /** settled */
    PAID,

    /** written off: owed, not collected, and no longer being chased */
    UNCOLLECTIBLE,

    /** cancelled as though it had never been issued. Not the same as refunded */
    VOID
}
