package com.confluo.common.jpa.mapper;

import com.confluo.common.jpa.entity.BaseJpaEntity;
import org.mapstruct.MapperConfig;
import org.mapstruct.Mapping;
import org.mapstruct.MappingInheritanceStrategy;
import org.mapstruct.ReportingPolicy;
import org.mapstruct.MappingTarget;

/**
 * Shared MapStruct configuration for every mapper that writes onto a {@link BaseJpaEntity}.
 *
 * <p>The prototype below carries the ignores that would otherwise be repeated on each write method:
 * the id is assigned on persist, the timestamps are maintained by Hibernate, and the soft-delete
 * markers are the service's business. Because the inheritance strategy is
 * {@link MappingInheritanceStrategy#AUTO_INHERIT_ALL_FROM_CONFIG}, the configuration is matched on
 * assignable types rather than on a signature, so it applies to every method whose
 * {@link MappingTarget} is a {@link BaseJpaEntity} — create and update overloads alike — and merges
 * with whatever mappings the method declares itself.</p>
 *
 * <p>Mappers using this configuration take {@code @Mapper(config = AuditableMapperConfig.class)};
 * the component model comes from here, so they no longer declare it.</p>

 * <p>An unmapped target property fails the build. The default is a warning, and a warning is not
 * enough here: a field whose accessor is spelled differently on the two sides - a boolean called
 * {@code isEuMember}, whose getter MapStruct reads as {@code euMember} - would otherwise compile
 * cleanly and return the wrong answer at runtime.</p>
 */
@MapperConfig(
        componentModel = "spring",
        mappingInheritanceStrategy = MappingInheritanceStrategy.AUTO_INHERIT_ALL_FROM_CONFIG,
        unmappedTargetPolicy = ReportingPolicy.ERROR
)
public interface AuditableMapperConfig {

    /**
     * Configuration prototype: never called and never implemented.
     * @param entity entity being written to
     * @param request request being copied to
     */
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    @Mapping(target = "disabledAt", ignore = true)
    @Mapping(target = "deletedAt", ignore = true)
    void updateAuditable(@MappingTarget BaseJpaEntity entity, Object request);
}
