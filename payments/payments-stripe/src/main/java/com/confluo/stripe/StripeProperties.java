package com.confluo.stripe;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * The credentials this service talks to Stripe with, and the secrets it verifies Stripe by.
 *
 * <p>All three are required. That is a deliberate break from the habit of only declaring what
 * something already reads: a missing secret key is found at the first charge and a missing signing
 * secret at the first webhook, and both of those are hours after the deploy, in production, with
 * money involved. {@code @NotBlank} moves the discovery to startup, where it is free.</p>
 *
 * <p>Secrets only, and that is the whole of this module's configuration. How a service behaves -
 * what a sale costs, whether a refund takes the seller's share back - is that service's own
 * settings, not these: a decision nobody needs to keep secret does not belong in the module that
 * holds the keys.</p>
 *
 * <p>The two signing secrets are different values, and using one in place of the other fails every
 * verification rather than some of them. Stripe issues one per registered endpoint, and the
 * platform's own events and its connected accounts' events are two endpoints - an account's
 * verification state arrives on the second, and a subscription's renewal on the first.</p>
 *
 * @param secretKey the account's API key, which is full authority over its money
 * @param webhookSecret the signing secret for the platform endpoint, and the only thing that makes
 *                      a request to it trustworthy
 * @param connectWebhookSecret the signing secret for the connected-accounts endpoint
 */
@Validated
@ConfigurationProperties(prefix = "confluo.stripe")
public record StripeProperties(

        @NotBlank
        String secretKey,

        @NotBlank
        String webhookSecret,

        @NotBlank
        String connectWebhookSecret
) {
}
