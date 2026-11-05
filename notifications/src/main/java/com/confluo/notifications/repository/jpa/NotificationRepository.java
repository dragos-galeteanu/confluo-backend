package com.confluo.notifications.repository.jpa;

import com.confluo.common.jpa.repository.BaseJpaRepository;
import com.confluo.notifications.entity.Notification;
import com.confluo.notifications.entity.NotificationStatus;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;

/**
 * The messages that should exist, and the state each has got to.
 *
 * <p>What the dispatcher runs is <strong>not</strong> here. Claiming due rows needs
 * {@code FOR UPDATE SKIP LOCKED}, so that query lives in its own component next to the dispatcher
 * rather than as a derived method pretending to be ordinary - the same separation common-amqp makes
 * between its outbox repository and its drainer. What is here is what the rest of the service asks:
 * the delivery log, the deduplication check, and the gauge the health indicator reports on.</p>
 */
@Repository
public interface NotificationRepository extends BaseJpaRepository<Notification> {

    /**
     * The message already sent under a deduplication key.
     *
     * <p>The unique partial index is what actually decides - this is the cheap look before the
     * insert, so the usual case costs a query rather than a caught constraint violation. It returns
     * the message rather than a boolean because a caller that retried deserves to be handed what
     * already exists, which is what makes the send route idempotent on the key.</p>
     *
     * @param dedupKey the key, which must not be null
     * @return the existing message, or empty
     */
    Optional<Notification> findByDedupKey(String dedupKey);

    /**
     * The oldest row still waiting, which is what the health indicator reports the age of.
     *
     * <p>The age and not the count, for the reason common-amqp's outbox indicator gives: a large
     * backlog seconds old is a busy service, while one row an hour old is a broken one.</p>
     *
     * <p>Written out rather than derived. Spring Data's method names cannot select a single column -
     * a {@code findCreatedAtBy...} would be parsed as returning the entity and fail at startup -
     * and {@code min} answers the question without ordering and loading a row to read one field
     * off it.</p>
     *
     * @param status the status to look at, which is {@link NotificationStatus#PENDING}
     * @return when the oldest such row was created, or empty when there are none
     */
    @Query("SELECT MIN(n.createdAt) FROM Notification n WHERE n.status = :status")
    Optional<Instant> findOldestCreatedAt(@Param("status") NotificationStatus status);
}
