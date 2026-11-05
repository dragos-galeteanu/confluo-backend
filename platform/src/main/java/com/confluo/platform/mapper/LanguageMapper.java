package com.confluo.platform.mapper;

import com.confluo.common.jpa.mapper.AuditableMapperConfig;
import com.confluo.common.mapper.BaseMapper;
import com.confluo.platform.dto.language.LanguageCreateRequest;
import com.confluo.platform.dto.language.LanguageResponse;
import com.confluo.platform.dto.language.LanguageUpdateRequest;
import com.confluo.platform.entity.Language;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;

/**
 * Converts between {@link Language} and its request and response objects.
 */
@Mapper(config = AuditableMapperConfig.class)
public interface LanguageMapper extends BaseMapper<Language, LanguageCreateRequest, LanguageUpdateRequest, LanguageResponse> {

    /** The record keeps the {@code is} prefix; the entity's accessors drop it. */
    @Override
    @Mapping(target = "isRightToLeft", source = "rightToLeft")
    LanguageResponse toResponse(Language entity);

    @Override
    @Mapping(target = "rightToLeft", source = "isRightToLeft")
    void createEntityFromRequest(@MappingTarget Language entity, LanguageCreateRequest request);

    @Override
    @Mapping(target = "rightToLeft", source = "isRightToLeft")
    void updateEntityFromRequest(@MappingTarget Language entity, LanguageUpdateRequest request);
}
