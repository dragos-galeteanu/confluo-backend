package com.confluo.payments.billing.discount;

/**
 * How a discount reduces what is owed.
 *
 * <p>The two Stripe supports on a coupon, and the reason {@link #FIXED} needs a currency beside its
 * amount while {@link #PERCENTAGE} does not: ten off is a different discount in every currency,
 * and ten per cent is not.</p>
 */
public enum DiscountKind {

    /** a proportion of the amount, valid in any currency */
    PERCENTAGE,

    /** an amount in minor units, meaningless without the currency stored next to it */
    FIXED
}
