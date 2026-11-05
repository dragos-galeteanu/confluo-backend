package com.confluo.payments.billing.subscription.dto;

import com.confluo.payments.pricing.BillingInterval;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Ask for a Checkout session so somebody can subscribe to a tier.
 *
 * <p>The tier is named by its key rather than by a price id, because a key is the only name of a
 * tier written down outside this service - a client that had to know price ids would break every
 * time a price changed, which is exactly when it must not.</p>
 *
 * <p>So the price is resolved here from the key, the currency and the period, and it is the
 * <strong>active</strong> one: a subscription is always sold at what the tier costs today.</p>
 *
 * @param planKey which tier, by its stable name
 * @param buyerFirebaseUid who is subscribing
 * @param currency uppercase ISO 4217
 * @param billingInterval whether they are paying monthly or yearly
 * @param intervalCount how many of those periods one charge covers; 1 unless the tier is sold
 *                      quarterly or similar
 * @param promotionCode a discount code the subscriber typed, or null.
 *                      <strong>The only way a code can be applied</strong>: this service does not
 *                      let Stripe collect one, because Stripe cannot enforce a per-person
 *                      redemption limit and this service can
 */
public record SubscriptionCheckoutRequest(

        @NotBlank
        @Size(max = 64)
        String planKey,

        @NotBlank
        @Size(max = 128)
        String buyerFirebaseUid,

        @NotBlank
        @Pattern(regexp = "^[A-Z]{3}$", message = "must be an uppercase ISO 4217 code")
        String currency,

        @NotNull
        BillingInterval billingInterval,

        @Min(1)
        int intervalCount,

        @Size(max = 64)
        String promotionCode
) {
}
