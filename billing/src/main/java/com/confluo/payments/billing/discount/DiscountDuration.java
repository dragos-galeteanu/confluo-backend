package com.confluo.payments.billing.discount;

/**
 * How long a discount keeps applying to a subscription.
 *
 * <p>Stripe's three, and only meaningful for recurring charges - a one-off payment is discounted
 * once whatever this says. {@link #REPEATING} is the one that needs a number of months stored with
 * it; the other two would make that column a contradiction, which is why the schema requires it to
 * be null for them.</p>
 */
public enum DiscountDuration {

    /** the first invoice only */
    ONCE,

    /** a set number of months, which has to be stored alongside */
    REPEATING,

    /** every invoice, for as long as the subscription lives */
    FOREVER
}
