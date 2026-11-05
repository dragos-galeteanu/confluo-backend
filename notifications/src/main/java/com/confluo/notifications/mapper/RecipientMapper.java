package com.confluo.notifications.mapper;

import com.confluo.common.jpa.mapper.AuditableMapperConfig;
import com.confluo.common.mapper.BaseMapper;
import com.confluo.notifications.dto.recipient.RecipientCreateRequest;
import com.confluo.notifications.dto.recipient.RecipientResponse;
import com.confluo.notifications.dto.recipient.RecipientUpdateRequest;
import com.confluo.notifications.entity.Recipient;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;

/**
 * Converts between {@link Recipient} and its request and response objects.
 *
 * <p>Three fields are ignored on the way in and none on the way out. {@code source} and
 * {@code lastSyncedAt} are the service's to set - provenance is not something a caller may claim -
 * and {@code firebaseUid} is ignored on update because it is the identity the row stands for, which
 * the service refuses to change rather than silently applying.</p>
 */
@Mapper(config = AuditableMapperConfig.class)
public interface RecipientMapper extends BaseMapper<Recipient, RecipientCreateRequest, RecipientUpdateRequest, RecipientResponse> {

    @Override
    RecipientResponse toResponse(Recipient entity);

    @Override
    @Mapping(target = "source", ignore = true)
    @Mapping(target = "lastSyncedAt", ignore = true)
    void createEntityFromRequest(@MappingTarget Recipient entity, RecipientCreateRequest request);

    @Override
    @Mapping(target = "firebaseUid", ignore = true)
    @Mapping(target = "source", ignore = true)
    @Mapping(target = "lastSyncedAt", ignore = true)
    void updateEntityFromRequest(@MappingTarget Recipient entity, RecipientUpdateRequest request);
}
