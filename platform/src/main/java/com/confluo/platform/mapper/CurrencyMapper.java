package com.confluo.platform.mapper;

import com.confluo.common.jpa.mapper.AuditableMapperConfig;
import com.confluo.common.mapper.BaseMapper;
import com.confluo.platform.dto.currency.CurrencyCreateRequest;
import com.confluo.platform.dto.currency.CurrencyResponse;
import com.confluo.platform.dto.currency.CurrencyUpdateRequest;
import com.confluo.platform.entity.Currency;
import org.mapstruct.Mapper;
import org.mapstruct.MappingTarget;

/**
 * Converts between {@link Currency} and its request and response objects.
 */
@Mapper(config = AuditableMapperConfig.class)
public interface CurrencyMapper extends BaseMapper<Currency, CurrencyCreateRequest, CurrencyUpdateRequest, CurrencyResponse> {

    @Override
    CurrencyResponse toResponse(Currency entity);

    @Override
    void createEntityFromRequest(@MappingTarget Currency entity, CurrencyCreateRequest request);

    @Override
    void updateEntityFromRequest(@MappingTarget Currency entity, CurrencyUpdateRequest request);
}
