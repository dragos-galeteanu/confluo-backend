package com.confluo.notifications.repository.mongo;

import com.confluo.common.mongo.repository.BaseMongoRepository;
import com.confluo.notifications.document.NotificationRenderedMessage;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

/**
 * The words of each message, by the notification they belong to.
 *
 * <p>Always reached by {@code notificationId} rather than by the document's own identifier, which
 * nothing outside this collection ever holds.</p>
 */
@Repository
public interface NotificationRenderedMessageRepository extends BaseMongoRepository<NotificationRenderedMessage> {

    /**
     * The content of one notification.
     * @param notificationId the notification
     * @return its words, or empty - which for a row past {@code PENDING} would mean something is
     *         wrong, since the content is written before the row is claimable
     */
    Optional<NotificationRenderedMessage> findByNotificationId(UUID notificationId);
}
