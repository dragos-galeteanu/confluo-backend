package com.confluo.payments.pricing;

/**
 * Which side of the marketplace a plan is sold to.
 *
 * <p>Both sides subscribe, to entirely different things, and one plan is never for both. The split
 * is stored rather than inferred from the plan's entitlements because it is what a listing filters
 * on: showing a provider's tiers to a customer is the kind of mistake that is only noticed from the
 * outside.</p>
 */
public enum PlanAudience {

    /**
     * sold to the people with connected accounts. These tiers can carry a reduced platform fee,
     * which is where the subscription half of the business meets the marketplace half.
     */
    PROVIDER,

    /** sold to the people who buy. Entitlements only; no bearing on anyone's fee */
    CUSTOMER
}
