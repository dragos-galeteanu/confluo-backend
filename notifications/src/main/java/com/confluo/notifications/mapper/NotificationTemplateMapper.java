package com.confluo.notifications.mapper;

import com.confluo.common.mapper.BaseMapper;
import com.confluo.notifications.document.NotificationTemplate;
import com.confluo.notifications.dto.template.NotificationTemplateCreateRequest;
import com.confluo.notifications.dto.template.NotificationTemplateResponse;
import com.confluo.notifications.dto.template.NotificationTemplateUpdateRequest;
import org.bson.types.ObjectId;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;
import org.mapstruct.ReportingPolicy;

/**
 * Converts between {@link NotificationTemplate} and its request and response objects.
 *
 * <p>Configured here rather than through {@code AuditableMapperConfig}, which is written against
 * {@code BaseJpaEntity} and would not match a document. The same two settings matter: the component
 * model, and an unmapped target being an error rather than a warning - a field whose accessor is
 * spelled differently on the two sides would otherwise compile cleanly and return the wrong
 * answer.</p>
 *
 * <p>Six fields are ignored on the way in. The identifier and the two timestamps are the store's,
 * the two lifecycle markers are the service's, and {@code version} is a counter the service bumps -
 * none of them is something a caller may state. The three that identify a template are ignored on
 * update as well, because the service refuses to change them rather than silently applying them.</p>
 */
@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface NotificationTemplateMapper
        extends BaseMapper<NotificationTemplate, NotificationTemplateCreateRequest, NotificationTemplateUpdateRequest, NotificationTemplateResponse> {

    @Override
    NotificationTemplateResponse toResponse(NotificationTemplate entity);

    @Override
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    @Mapping(target = "disabledAt", ignore = true)
    @Mapping(target = "deletedAt", ignore = true)
    @Mapping(target = "version", ignore = true)
    void createEntityFromRequest(@MappingTarget NotificationTemplate entity, NotificationTemplateCreateRequest request);

    @Override
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    @Mapping(target = "disabledAt", ignore = true)
    @Mapping(target = "deletedAt", ignore = true)
    @Mapping(target = "version", ignore = true)
    @Mapping(target = "templateKey", ignore = true)
    @Mapping(target = "channel", ignore = true)
    @Mapping(target = "locale", ignore = true)
    void updateEntityFromRequest(@MappingTarget NotificationTemplate entity, NotificationTemplateUpdateRequest request);

    /**
     * The identifier as callers see it.
     * @param id the document's identifier, or null before it is first saved
     * @return its hex string, or null
     */
    default String objectIdToString(ObjectId id) {
        return id == null ? null : id.toHexString();
    }
}
