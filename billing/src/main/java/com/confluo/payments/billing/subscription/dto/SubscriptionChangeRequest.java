package com.confluo.payments.billing.subscription.dto;

import com.confluo.payments.pricing.BillingInterval;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Move a subscription onto a different tier or billing period.
 *
 * <p><strong>No currency.</strong> Stripe will not change a subscription's currency, so the new
 * price is looked up in the one the subscription is already billed in - and asking for a currency
 * here would invite a request this platform has to refuse for a reason that is not its own.</p>
 *
 * <p>The change takes effect at once and the difference is invoiced immediately, which is what this
 * platform chose over letting it wait for the next renewal.</p>
 *
 * @param planKey which tier to move to, by its stable name. The same tier is allowed if the
 *                billing period is what is changing
 * @param billingInterval the period to bill on from now on
 * @param intervalCount how many of those periods one charge covers
 */
public record SubscriptionChangeRequest(

        @NotBlank
        @Size(max = 64)
        String planKey,

        @NotNull
        BillingInterval billingInterval,

        @Min(1)
        int intervalCount
) {
}
