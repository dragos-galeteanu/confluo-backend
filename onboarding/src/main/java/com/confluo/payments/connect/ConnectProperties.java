package com.confluo.payments.connect;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Where Stripe sends a seller during and after onboarding.
 *
 * <p>Both are required and neither is optional to Stripe: an account link cannot be created
 * without them, so a missing one fails the first seller who tries to onboard rather than at
 * startup - which is why they are validated here instead.</p>
 *
 * @param onboardingReturnUrl where Stripe sends a seller who finished the form. Reaching it
 *                            does <strong>not</strong> mean they are verified: Stripe returns
 *                            them as soon as the form is submitted, and whether they can be
 *                            charged for is decided later and announced by
 *                            {@code account.updated}. A page that treats arrival as success
 *                            will tell sellers they are live when they are not
 * @param onboardingRefreshUrl where Stripe sends a seller whose link expired or was already
 *                             used. An account link is single-use and lives minutes, so this
 *                             has to be a page that asks this service for a fresh link and
 *                             redirects again - not an error page
 */
@Validated
@ConfigurationProperties(prefix = "confluo.payments.connect")
public record ConnectProperties(
        @NotBlank String onboardingReturnUrl,
        @NotBlank String onboardingRefreshUrl) {
}
