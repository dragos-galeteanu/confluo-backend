package com.confluo.stripe;

import java.time.Instant;
import java.util.List;

/**
 * The three things this service does to a Stripe connected account.
 *
 * <p>A seam, as {@link CheckoutSessionGateway} is, and for the same reason: what is behind it is a
 * network call, and what is in front of it - when an account is created, which kind of link a
 * seller gets, how Stripe's flags become a state - is the part worth being able to exercise.</p>
 *
 * <p>Unlike the checkout gateway this one takes plain arguments rather than Stripe's parameter
 * types. There is only one shape of account this platform creates, so the decisions that would be
 * worth reading in a parameter builder - who bears losses, who pays the fees, who collects the
 * requirements - are fixed, and belong in the implementation beside the reason for each.</p>
 */
public interface ConnectAccountGateway {

    /**
     * Creates a connected account for a seller.
     *
     * @param firebaseUid whose account it is, carried into Stripe's metadata
     * @param country uppercase ISO-3166 alpha-2; immutable at Stripe
     * @param defaultCurrency uppercase ISO 4217
     * @param email where Stripe may write to them, or null
     * @return the account as Stripe created it
     */
    ConnectedAccountSnapshot create(
            String firebaseUid, String country, String defaultCurrency, String email);

    /**
     * Reads an account's current state from Stripe.
     *
     * <p>For reconciling a row whose {@code account.updated} was missed. Not the ordinary path:
     * the webhook is, and this is the repair.</p>
     *
     * @param stripeAccountId Stripe's {@code acct_} identifier
     * @return the account as Stripe has it
     */
    ConnectedAccountSnapshot retrieve(String stripeAccountId);

    /**
     * Mints a single-use onboarding link.
     *
     * @param stripeAccountId Stripe's {@code acct_} identifier
     * @param accountUpdate true for the update form, which is what a seller who has already
     *                      submitted their details needs; false for first-time onboarding
     * @param returnUrl where Stripe sends a seller who finished the form. Passed in rather than
     *                  read from configuration here: where this platform's pages live is this
     *                  platform's business, and a gateway that knew it would be a gateway that
     *                  could not be moved
     * @param refreshUrl where Stripe sends a seller whose link expired or was already used, which
     *                   has to be a page that asks for a fresh link rather than an error page
     * @return the link and when it expires
     */
    OnboardingLink createOnboardingLink(
            String stripeAccountId, boolean accountUpdate, String returnUrl, String refreshUrl);

    /**
     * What Stripe says about an account.
     *
     * @param stripeAccountId Stripe's own identifier
     * @param country where the account is
     * @param defaultCurrency what it settles in
     * @param detailsSubmitted whether onboarding was completed
     * @param chargesEnabled whether sales may be put through
     * @param payoutsEnabled whether money may reach the bank
     * @param disabledReason why either is withheld, or null
     * @param currentlyDue what Stripe still wants
     * @param pastDue what is overdue
     */
    record ConnectedAccountSnapshot(
            String stripeAccountId,
            String country,
            String defaultCurrency,
            boolean detailsSubmitted,
            boolean chargesEnabled,
            boolean payoutsEnabled,
            String disabledReason,
            List<String> currentlyDue,
            List<String> pastDue) {
    }

    /**
     * A single-use onboarding link.
     *
     * @param url Stripe's hosted page
     * @param expiresAt when it stops working
     */
    record OnboardingLink(String url, Instant expiresAt) {
    }
}
