package com.confluo.payments.pricing;

import com.confluo.common.jpa.mapper.AuditableMapperConfig;
import com.confluo.payments.pricing.dto.PlanPriceCreateRequest;
import com.confluo.payments.pricing.dto.PlanPriceResponse;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;

/**
 * Converts between {@link PlanPrice} and its request and response objects.
 *
 * <p>Deliberately not a {@code BaseMapper}. That interface requires an update method, and a price
 * has none: Stripe's prices are immutable, so a change is a new row rather than an edit to this
 * one. Declaring an update method that nothing may call would be a promise this resource cannot
 * keep.</p>
 *
 * <p>{@code plan}, {@code stripePriceId} and {@code active} are all the service's to set - the tier
 * comes from the route, the Stripe id from the reconciliation, and whether a price is the one
 * being sold is decided by adding a newer one.</p>
 */
@Mapper(config = AuditableMapperConfig.class)
public interface PlanPriceMapper {

    /**
     * A price as the API returns it.
     * @param entity the price
     * @return the response
     */
    @Mapping(target = "planId", source = "plan.id")
    @Mapping(target = "planKey", source = "plan.key")
    PlanPriceResponse toResponse(PlanPrice entity);

    /**
     * Copies a request onto a new price.
     * @param entity the price being created
     * @param request what was asked for
     */
    @Mapping(target = "plan", ignore = true)
    @Mapping(target = "stripePriceId", ignore = true)
    @Mapping(target = "active", ignore = true)
    void createEntityFromRequest(@MappingTarget PlanPrice entity, PlanPriceCreateRequest request);
}
