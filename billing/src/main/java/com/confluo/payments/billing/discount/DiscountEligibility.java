package com.confluo.payments.billing.discount;

import java.util.UUID;

/**
 * Whether a discount may be used, and if not, why.
 *
 * <p>A reason rather than a boolean, because every one of them is a different thing to tell the
 * person at the checkout: "that code does not exist" and "you have already used that code" and
 * "that code needs an order of at least 20 EUR" are three different conversations, and collapsing
 * them into "invalid" makes the platform look broken when it is working correctly.</p>
 *
 * <p>Checked here as well as at Stripe. Stripe validates a promotion code again at redemption and
 * is the one that cannot be bypassed; this exists so the refusal happens before the payer is sent
 * anywhere, with wording this platform chose.</p>
 *
 * @param reason why, which is {@link Reason#ELIGIBLE} when it may be used
 * @param discountId the discount, or null when there is none to name
 * @param code the code as it was looked up, uppercased
 */
public record DiscountEligibility(Reason reason, UUID discountId, String code) {

    /** @return whether the discount may be used */
    public boolean isEligible() {
        return reason == Reason.ELIGIBLE;
    }

    /**
     * Builds an eligible answer.
     * @param discountId the discount
     * @param code its code, or null
     * @return the answer
     */
    public static DiscountEligibility eligible(UUID discountId, String code) {
        return new DiscountEligibility(Reason.ELIGIBLE, discountId, code);
    }

    /**
     * Builds a refusal.
     * @param reason why
     * @param discountId the discount, or null if none was found
     * @param code the code looked up
     * @return the answer
     */
    public static DiscountEligibility refused(Reason reason, UUID discountId, String code) {
        return new DiscountEligibility(reason, discountId, code);
    }

    /** Why a discount may or may not be used. */
    public enum Reason {

        /** it may be used */
        ELIGIBLE,

        /** no discount has that code */
        UNKNOWN_CODE,

        /** it existed and has been withdrawn */
        WITHDRAWN,

        /** its validity window has not started */
        NOT_YET_VALID,

        /** its validity window has passed */
        EXPIRED,

        /** it has been used as many times as it allows, by everybody together */
        FULLY_REDEEMED,

        /** this person has used it as many times as they may */
        ALREADY_REDEEMED,

        /** it is for a first purchase, and this person has paid before */
        NOT_A_FIRST_TRANSACTION,

        /** the order is smaller than the discount's threshold */
        BELOW_MINIMUM,

        /**
         * the discount is a fixed amount in another currency, or its threshold is. A percentage
         * applies in any currency; an amount does not.
         */
        WRONG_CURRENCY,

        /** it is restricted to other tiers than the one being bought */
        NOT_FOR_THIS_PLAN
    }
}
