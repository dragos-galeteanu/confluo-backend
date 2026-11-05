package com.confluo.payments.billing.discount;

import com.confluo.common.jpa.mapper.AuditableMapperConfig;
import com.confluo.payments.billing.discount.dto.DiscountCreateRequest;
import com.confluo.payments.billing.discount.dto.DiscountResponse;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;

/**
 * Converts between {@link Discount} and its request and response objects.
 *
 * <p>Deliberately not a {@code BaseMapper}, like {@code PlanPriceMapper} and for the same reason:
 * that interface requires an update method, and a discount has none. A Stripe coupon's shape cannot
 * be edited, so changing what a discount is worth means creating another.</p>
 *
 * <p>{@code code} is ignored on the way in because the service uppercases it - the column is
 * constrained to upper case so that the unique index can answer "is this code taken?" exactly, and
 * letting the mapper copy whatever was typed would defeat that. The two Stripe ids are ignored
 * because they are the reconciliation's result; {@code timesRedeemed} is not a field on the entity
 * at all and is counted by the service.</p>
 */
@Mapper(config = AuditableMapperConfig.class)
public interface DiscountMapper {

    /**
     * A discount as the API returns it, without its redemption count.
     *
     * <p>{@code timesRedeemed} is left at zero here and filled in by the service, which is the only
     * thing that can count it - the number lives in another table.</p>
     *
     * @param entity the discount
     * @return the response, with {@code timesRedeemed} unset
     */
    @Mapping(target = "timesRedeemed", ignore = true)
    DiscountResponse toResponse(Discount entity);

    /**
     * Copies a request onto a new discount.
     * @param entity the discount being created
     * @param request what was asked for
     */
    @Mapping(target = "code", ignore = true)
    @Mapping(target = "stripeCouponId", ignore = true)
    @Mapping(target = "stripePromotionCodeId", ignore = true)
    void createEntityFromRequest(@MappingTarget Discount entity, DiscountCreateRequest request);
}
