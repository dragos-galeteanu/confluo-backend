package com.confluo.platform.mapper;

import com.confluo.common.jpa.mapper.AuditableMapperConfig;
import com.confluo.common.mapper.BaseMapper;
import com.confluo.platform.dto.country.CountryCreateRequest;
import com.confluo.platform.dto.country.CountryResponse;
import com.confluo.platform.dto.country.CountryUpdateRequest;
import com.confluo.platform.entity.Country;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;

/**
 * Converts between {@link Country} and its request and response objects.
 *
 * <p>Writing the two associations is not done here. A request carries the currency and language as
 * ids, and turning an id into an entity means a lookup that can fail - which is the service's
 * business, not a mapper's. Reading them is only a matter of taking the id back off, which is.</p>
 */
@Mapper(config = AuditableMapperConfig.class)
public interface CountryMapper extends BaseMapper<Country, CountryCreateRequest, CountryUpdateRequest, CountryResponse> {

    /**
     * The record keeps the {@code is} prefix where the entity's accessors drop it, and the two
     * associations are flattened to their ids.
     */
    @Override
    @Mapping(target = "defaultCurrencyId", source = "defaultCurrency.id")
    @Mapping(target = "defaultLanguageId", source = "defaultLanguage.id")
    @Mapping(target = "isEuVatMember", source = "euVatMember")
    @Mapping(target = "isEuMember", source = "euMember")
    @Mapping(target = "isEeaMember", source = "eeaMember")
    @Mapping(target = "isSchengenMember", source = "schengenMember")
    CountryResponse toResponse(Country entity);

    @Override
    @Mapping(target = "euVatMember", source = "isEuVatMember")
    @Mapping(target = "euMember", source = "isEuMember")
    @Mapping(target = "eeaMember", source = "isEeaMember")
    @Mapping(target = "schengenMember", source = "isSchengenMember")
    @Mapping(target = "defaultCurrency", ignore = true)
    @Mapping(target = "defaultLanguage", ignore = true)
    void createEntityFromRequest(@MappingTarget Country entity, CountryCreateRequest request);

    @Override
    @Mapping(target = "euVatMember", source = "isEuVatMember")
    @Mapping(target = "euMember", source = "isEuMember")
    @Mapping(target = "eeaMember", source = "isEeaMember")
    @Mapping(target = "schengenMember", source = "isSchengenMember")
    @Mapping(target = "defaultCurrency", ignore = true)
    @Mapping(target = "defaultLanguage", ignore = true)
    void updateEntityFromRequest(@MappingTarget Country entity, CountryUpdateRequest request);
}
