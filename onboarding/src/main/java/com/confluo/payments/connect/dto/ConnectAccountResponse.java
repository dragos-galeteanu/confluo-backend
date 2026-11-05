package com.confluo.payments.connect.dto;

import com.confluo.payments.connect.ConnectedAccountState;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A seller's account, as the API returns it.
 *
 * <p>{@code state} is the field to act on; the three booleans are what it is derived from and are
 * returned so that a seller can be shown <em>why</em>. {@code requirementsCurrentlyDue} is the
 * actionable part - it is Stripe's own list of what is still missing, and showing it is the
 * difference between "your account is restricted" and "Stripe still needs your ID document".</p>
 *
 * <p>Nothing here is this platform's judgement. Every field is Stripe's answer, recorded by the
 * {@code account.updated} webhook.</p>
 *
 * @param id this service's id for the account
 * @param firebaseUid whose it is
 * @param stripeAccountId Stripe's own {@code acct_} identifier
 * @param country where the seller is; immutable
 * @param defaultCurrency what Stripe settles them in
 * @param state what the account can currently do, derived from the three flags below
 * @param detailsSubmitted whether onboarding was completed
 * @param chargesEnabled whether a sale may be put through for them
 * @param payoutsEnabled whether money may reach their bank
 * @param disabledReason Stripe's reason for withholding either, or null
 * @param requirementsCurrentlyDue what Stripe still wants, as it names it
 * @param requirementsPastDue what is overdue, which is what is holding payouts
 * @param onboardingCompletedAt when onboarding was first completed, or null
 * @param lastAccountEventAt when Stripe last told this service anything about the account
 */
public record ConnectAccountResponse(
        UUID id,
        String firebaseUid,
        String stripeAccountId,
        String country,
        String defaultCurrency,
        ConnectedAccountState state,
        boolean detailsSubmitted,
        boolean chargesEnabled,
        boolean payoutsEnabled,
        String disabledReason,
        List<String> requirementsCurrentlyDue,
        List<String> requirementsPastDue,
        Instant onboardingCompletedAt,
        Instant lastAccountEventAt
) {
}
