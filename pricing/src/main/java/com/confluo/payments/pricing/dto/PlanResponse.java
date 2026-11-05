package com.confluo.payments.pricing.dto;

import com.confluo.payments.pricing.PlanAudience;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * A tier, as the API returns it.
 *
 * <p>{@code stripeProductId} is worth returning rather than hiding: null means the tier has not
 * been reconciled into Stripe, which is the difference between a tier that can be sold and one that
 * cannot - and the only way for whoever created it to see that the push failed.</p>
 *
 * <p>No prices. A listing of tiers would otherwise cost a query per row, and the prices of one
 * tier are their own route.</p>
 *
 * @param id this service's id for the tier
 * @param key the stable name other code refers to it by
 * @param audience which side of the marketplace it is sold to
 * @param name what the person buying it sees
 * @param description what it offers
 * @param entitlements what it grants
 * @param platformFeeBpsOverride the fee a provider on this tier pays, or null for the default
 * @param stripeProductId Stripe's own id, or null if it has not been reconciled
 * @param sortOrder where it sits in a listing
 * @param disabledAt when it stopped being offered, or null
 * @param createdAt when it was created
 * @param updatedAt when it last changed
 */
public record PlanResponse(
        UUID id,
        String key,
        PlanAudience audience,
        String name,
        String description,
        Map<String, Object> entitlements,
        Integer platformFeeBpsOverride,
        String stripeProductId,
        int sortOrder,
        Instant disabledAt,
        Instant createdAt,
        Instant updatedAt
) {
}
