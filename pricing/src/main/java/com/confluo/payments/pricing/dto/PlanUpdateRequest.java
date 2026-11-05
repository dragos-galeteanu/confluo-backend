package com.confluo.payments.pricing.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.Map;
import java.util.UUID;

/**
 * Change a tier.
 *
 * <p>Neither the key nor the audience can be changed, and both are absent rather than ignored so
 * that a caller cannot believe they have changed one. The key is what other code refers to the tier
 * by, so renaming it breaks every reference; the audience decides whether a fee override means
 * anything, so moving a tier between sides would quietly change what sellers are charged.</p>
 *
 * <p>The price is not here either. A Stripe price is immutable, so changing what a tier costs means
 * adding a price and deactivating the old one - see the prices routes.</p>
 *
 * @param id which tier
 * @param name what the person buying it sees
 * @param description what it offers, in prose
 * @param entitlements what it grants
 * @param platformFeeBpsOverride the fee a provider on this tier pays, or null for the default
 * @param sortOrder where it sits in a listing
 */
public record PlanUpdateRequest(

        @NotNull
        UUID id,

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
