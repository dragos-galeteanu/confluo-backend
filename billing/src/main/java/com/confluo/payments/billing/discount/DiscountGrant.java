package com.confluo.payments.billing.discount;

/**
 * How someone comes to have a discount.
 *
 * <p>Only {@link #PUBLIC_CODE} is something a payer types. The other three are attached to one
 * person by this platform, which is why a discount's code may be null: a goodwill credit has no
 * code to enter, and giving it one would make it redeemable by anybody who learned the string.</p>
 *
 * <p>Stored because the three granted kinds are indistinguishable once applied and are asked about
 * for different reasons - what a referral programme cost, what support gave away, how a launch
 * cohort converted.</p>
 */
public enum DiscountGrant {

    /** a code anyone may redeem, subject to its own restrictions */
    PUBLIC_CODE,

    /** earned by referring somebody, and attached to the referrer */
    REFERRAL,

    /** given by support to put something right */
    GOODWILL,

    /** granted to a named group, such as everyone who joined before a date */
    COHORT
}
