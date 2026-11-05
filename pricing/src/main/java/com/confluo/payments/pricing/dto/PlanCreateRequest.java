package com.confluo.payments.pricing.dto;

import com.confluo.payments.pricing.PlanAudience;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.Map;

/**
 * Create a tier somebody can subscribe to.
 *
 * <p>The plan is written here and pushed into Stripe as a Product, not the other way round: the
 * entitlements and the fee override mean nothing to Stripe, and a tier nobody can query locally is
 * one every other service has to make a network call to read.</p>
 *
 * <p>No prices. A plan has no price until one is added for a currency and a billing period, which
 * is a separate route - and deliberately, because a price is immutable once Stripe has it and
 * changing one means adding another.</p>
 *
 * @param key the stable name other code refers to this tier by, lowercase
 * @param audience which side of the marketplace it is sold to
 * @param name what the person buying it sees
 * @param description what it offers, in prose
 * @param entitlements what it grants, as a document other services read
 * @param platformFeeBpsOverride the platform fee a provider on this tier pays, in basis points, or
 *                               null for the default. Only a {@code PROVIDER} tier may set it: a
 *                               customer does not pay the platform fee, so an override on a
 *                               customer tier would silently never apply
 * @param sortOrder where it sits in a listing, lowest first
 */
public record PlanCreateRequest(

        @NotBlank
        @Size(max = 64)
        @Pattern(regexp = "^[a-z0-9_]+$", message = "must be lowercase letters, digits and underscores")
        String key,

        @NotNull
        PlanAudience audience,

        @NotBlank
        @Size(max = 255)
        String name,

        @Size(max = 4000)
        String description,

        Map<String, Object> entitlements,

        @Min(0)
        @Max(10000)
        Integer platformFeeBpsOverride,

        int sortOrder
) {
}
