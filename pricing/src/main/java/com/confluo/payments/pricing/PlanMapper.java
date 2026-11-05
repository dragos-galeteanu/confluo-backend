package com.confluo.payments.pricing;

import com.confluo.common.jpa.mapper.AuditableMapperConfig;
import com.confluo.common.mapper.BaseMapper;
import com.confluo.payments.pricing.dto.PlanCreateRequest;
import com.confluo.payments.pricing.dto.PlanResponse;
import com.confluo.payments.pricing.dto.PlanUpdateRequest;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;

/**
 * Converts between {@link Plan} and its request and response objects.
 *
 * <p>{@code stripeProductId} is ignored on both write methods: it is the reconciliation's result,
 * not something a caller may claim, and letting a request set it would point a tier at somebody
 * else's Stripe product.</p>
 *
 * <p>{@code key} and {@code audience} are ignored on update because neither may change - the
 * update request does not carry them at all, and the ignores are what make that explicit to
 * MapStruct rather than a silent unmapped-target failure.</p>
 */
@Mapper(config = AuditableMapperConfig.class)
public interface PlanMapper extends BaseMapper<Plan, PlanCreateRequest, PlanUpdateRequest, PlanResponse> {

    @Override
    PlanResponse toResponse(Plan entity);

    @Override
    @Mapping(target = "stripeProductId", ignore = true)
    void createEntityFromRequest(@MappingTarget Plan entity, PlanCreateRequest request);

    @Override
    @Mapping(target = "key", ignore = true)
    @Mapping(target = "audience", ignore = true)
    @Mapping(target = "stripeProductId", ignore = true)
    void updateEntityFromRequest(@MappingTarget Plan entity, PlanUpdateRequest request);
}
