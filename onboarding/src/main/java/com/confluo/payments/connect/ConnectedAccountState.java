package com.confluo.payments.connect;

/**
 * What a connected account can currently do, as one answer rather than four flags.
 *
 * <p>Derived, never stored. Stripe reports {@code details_submitted},
 * {@code charges_enabled}, {@code payouts_enabled} and a {@code disabled_reason}, and those four
 * are what the webhook writes; this is the question everything else actually asks. Keeping it out
 * of the table is the point - a stored copy would be a second place for the same fact to be true,
 * and the one that goes stale is always the copy.</p>
 *
 * <p>Each state corresponds to a different thing to do about it, which is why there are four and
 * not more: wait for the person, show them what Stripe still wants, carry on, or give up.</p>
 */
public enum ConnectedAccountState {

    /** onboarding was never finished, so Stripe has nothing to verify yet */
    NOT_STARTED,

    /**
     * submitted, but Stripe is withholding charges or payouts - something is still due, or the
     * account is under review. What is outstanding is in the account's own requirements.
     */
    RESTRICTED,

    /** charges and payouts both allowed. The only state in which a sale may be put through */
    ENABLED,

    /** Stripe has rejected the account. Terminal, and nothing this service does will change it */
    REJECTED;

    /**
     * Works out the state from what Stripe reported about the account.
     *
     * <p>A pure function of the four fields the webhook stores, so the answer cannot drift from
     * them. The rejection test is on the prefix rather than an exhaustive list of reasons, because
     * Stripe adds them: every reason that means rejected begins {@code rejected.}, and a new one
     * should land in the right state without a release here.</p>
     *
     * @param detailsSubmitted whether onboarding was completed
     * @param chargesEnabled whether the account may take payments
     * @param payoutsEnabled whether the account may be paid out
     * @param disabledReason Stripe's reason for withholding either, or null if it is not
     * @return the state those four describe
     */
    public static ConnectedAccountState of(
            boolean detailsSubmitted, boolean chargesEnabled, boolean payoutsEnabled, String disabledReason) {
        if (disabledReason != null && disabledReason.startsWith("rejected.")) {
            return REJECTED;
        }
        if (!detailsSubmitted) {
            return NOT_STARTED;
        }
        if (chargesEnabled && payoutsEnabled && disabledReason == null) {
            return ENABLED;
        }
        return RESTRICTED;
    }
}
