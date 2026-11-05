package com.confluo.stripe;

import com.stripe.StripeClient;
import com.stripe.exception.StripeException;
import com.stripe.model.Account;
import com.stripe.model.AccountLink;
import com.stripe.net.RequestOptions;
import com.stripe.param.AccountCreateParams;
import com.stripe.param.AccountLinkCreateParams;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.util.List;
import java.util.Locale;

/**
 * {@link ConnectAccountGateway} against Stripe.
 *
 * <h2>The account's controller, and why it is set this way</h2>
 *
 * <p>Four settings decide what kind of connected account this is, and each follows from a decision
 * already made elsewhere in this service:</p>
 *
 * <ul>
 *   <li><strong>losses: application</strong> - the platform bears disputes. It has to: under
 *       destination charges the platform is the merchant of record, which is what {@code Dispute}
 *       and its {@code transferReversedAmount} are about. Setting this to {@code stripe} would
 *       contradict the charge type;</li>
 *   <li><strong>fees: application</strong> - the platform pays Stripe's fees and collects its own
 *       cut as the application fee, which is what {@code PlatformFeeCalculator} computes;</li>
 *   <li><strong>requirement collection: stripe</strong> - Stripe collects the identity documents,
 *       on its own hosted pages. This is the whole reason onboarding is a redirect: none of that
 *       data passes through this platform, so none of it has to be stored or protected here;</li>
 *   <li><strong>dashboard: express</strong> - the seller gets Stripe's limited dashboard, where they
 *       can see their own payouts and update their bank details without this platform building any
 *       of it.</li>
 * </ul>
 *
 * <p>Nothing but the calls and the mapping. Any decision that appears here is in the wrong place -
 * except the four above, which are the shape of the account itself.</p>
 */
@RequiredArgsConstructor
@Slf4j
public class StripeConnectAccountGateway implements ConnectAccountGateway {

    private final StripeClient stripe;

    @Override
    public ConnectedAccountSnapshot create(
            String firebaseUid, String country, String defaultCurrency, String email) {

        AccountCreateParams.Builder params = AccountCreateParams.builder()
                .setCountry(country)
                // Stripe speaks lowercase; the column is uppercase, and this is the boundary.
                .setDefaultCurrency(defaultCurrency.toLowerCase(Locale.ROOT))
                .setController(AccountCreateParams.Controller.builder()
                        .setLosses(AccountCreateParams.Controller.Losses.builder()
                                .setPayments(AccountCreateParams.Controller.Losses.Payments.APPLICATION)
                                .build())
                        .setFees(AccountCreateParams.Controller.Fees.builder()
                                .setPayer(AccountCreateParams.Controller.Fees.Payer.APPLICATION)
                                .build())
                        .setRequirementCollection(
                                AccountCreateParams.Controller.RequirementCollection.STRIPE)
                        .setStripeDashboard(AccountCreateParams.Controller.StripeDashboard.builder()
                                .setType(AccountCreateParams.Controller.StripeDashboard.Type.EXPRESS)
                                .build())
                        .build())
                .putMetadata("firebase_uid", firebaseUid);

        if (email != null && !email.isBlank()) {
            params.setEmail(email);
        }

        try {
            Account account = stripe.accounts().create(params.build(),
                    RequestOptions.builder()
                            // Derived from the seller, so a retry after a lost response finds the
                            // account Stripe already made rather than giving them a second one.
                            .setIdempotencyKey("connect:" + firebaseUid)
                            .build());
            log.info("Stripe connected account created [uid={}, account={}, country={}]",
                    firebaseUid, account.getId(), country);
            return snapshot(account);
        } catch (StripeException e) {
            throw new StripeOperationException(
                    "Could not create a Stripe connected account for %s".formatted(firebaseUid), e);
        }
    }

    @Override
    public ConnectedAccountSnapshot retrieve(String stripeAccountId) {
        try {
            return snapshot(stripe.accounts().retrieve(stripeAccountId));
        } catch (StripeException e) {
            throw new StripeOperationException(
                    "Could not read Stripe connected account %s".formatted(stripeAccountId), e);
        }
    }

    @Override
    public OnboardingLink createOnboardingLink(
            String stripeAccountId, boolean accountUpdate, String returnUrl, String refreshUrl) {
        try {
            AccountLink link = stripe.accountLinks().create(
                    AccountLinkCreateParams.builder()
                            .setAccount(stripeAccountId)
                            .setType(accountUpdate
                                    ? AccountLinkCreateParams.Type.ACCOUNT_UPDATE
                                    : AccountLinkCreateParams.Type.ACCOUNT_ONBOARDING)
                            .setReturnUrl(returnUrl)
                            .setRefreshUrl(refreshUrl)
                            .build(),
                    // No idempotency key, deliberately: every call must produce a NEW link. These
                    // are single-use and expire in minutes, so reusing one would hand a seller the
                    // stale link that sent them here in the first place.
                    RequestOptions.builder().build());
            return new OnboardingLink(link.getUrl(), instant(link.getExpiresAt()));
        } catch (StripeException e) {
            throw new StripeOperationException(
                    "Could not create an onboarding link for %s".formatted(stripeAccountId), e);
        }
    }

    private ConnectedAccountSnapshot snapshot(Account account) {
        Account.Requirements requirements = account.getRequirements();
        return new ConnectedAccountSnapshot(
                account.getId(),
                account.getCountry() == null ? null : account.getCountry().toUpperCase(Locale.ROOT),
                account.getDefaultCurrency() == null
                        ? null
                        : account.getDefaultCurrency().toUpperCase(Locale.ROOT),
                Boolean.TRUE.equals(account.getDetailsSubmitted()),
                Boolean.TRUE.equals(account.getChargesEnabled()),
                Boolean.TRUE.equals(account.getPayoutsEnabled()),
                requirements == null ? null : requirements.getDisabledReason(),
                requirements == null || requirements.getCurrentlyDue() == null
                        ? List.of()
                        : List.copyOf(requirements.getCurrentlyDue()),
                requirements == null || requirements.getPastDue() == null
                        ? List.of()
                        : List.copyOf(requirements.getPastDue()));
    }

    private static Instant instant(Long epochSeconds) {
        return epochSeconds == null ? null : Instant.ofEpochSecond(epochSeconds);
    }
}
