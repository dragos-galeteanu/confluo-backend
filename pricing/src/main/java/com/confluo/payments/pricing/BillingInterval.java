package com.confluo.payments.pricing;

/**
 * How often a recurring price is charged.
 *
 * <p>Two of Stripe's four. Daily and weekly billing exist and are not sold here; adding one is a
 * value and a reconciliation, not a change to anything that reads this. The column is
 * {@code billing_interval} rather than {@code interval} because {@code INTERVAL} is a type name in
 * PostgreSQL, and a column called that has to be quoted everywhere forever.</p>
 */
public enum BillingInterval {

    /** charged every month, or every {@code interval_count} months */
    MONTH,

    /** charged every year, or every {@code interval_count} years */
    YEAR
}
