package com.confluo.notifications.mapper;

import com.confluo.notifications.dto.notification.NotificationResponse;
import com.confluo.notifications.entity.Notification;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;

/**
 * Converts a {@link Notification} to its response object.
 *
 * <p>One direction only. A notification is not built from a request field by field - the pipeline
 * decides most of what goes on it, and the few things a caller does supply arrive mixed in with
 * the things that determine whether there is a message at all. A mapper that wrote a request onto
 * the entity would be a mapper that could write a status.</p>
 */
@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface NotificationMapper {

    /**
     * A notification as the API returns it.
     *
     * <p>Both recipient fields are read through the association, which is lazy - so this has to be
     * called inside the transaction that loaded the notification. MapStruct generates the null
     * checks, which matters because an ad-hoc send has no recipient at all.</p>
     *
     * @param entity the notification
     * @return the response
     */
    @Mapping(target = "recipientId", source = "recipient.id")
    @Mapping(target = "firebaseUid", source = "recipient.firebaseUid")
    NotificationResponse toResponse(Notification entity);
}
