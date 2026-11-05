package com.confluo.payments.marketplace;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * What a marketplace sale costs, and where Stripe sends the payer afterwards.
 *
 * @param platformFeeBasisPoints the platform's cut of a sale, in basis points - 250 is 2.5%.
 *                               A provider on a paid tier can be charged less; see
 *                               {@code Plan.platformFeeBpsOverride}, which this is the default
 *                               for. Capped at 10000 because a fee above the whole sale is not
 *                               a fee
 * @param successUrl where Stripe returns a payer who paid. {@code {CHECKOUT_SESSION_ID}} in it
 *                   is replaced by Stripe with the session's id, which is how the page that
 *                   receives them can say what was bought - and it must not be trusted as
 *                   proof of payment, because a payer can open that URL themselves. Only the
 *                   webhook proves anything
 * @param cancelUrl where Stripe returns a payer who gave up
 */
@Validated
@ConfigurationProperties(prefix = "confluo.payments.checkout")
public record CheckoutProperties(
        @DefaultValue("250") @Min(0) @Max(10000) int platformFeeBasisPoints,
        @NotBlank String successUrl,
        @NotBlank String cancelUrl) {
}
