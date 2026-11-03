package com.confluo.notifications.repository.jpa;

import com.confluo.common.jpa.repository.BaseJpaRepository;
import com.confluo.notifications.entity.DeliveryAttempt;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * What came of each call to a provider.
 *
 * <p>The shared base, like every other repository here. Both queries below are the ones actually
 * used - an attempt is read as the history of one message, or found by the id a provider's callback
 * names - so the filtering and pagination the base also brings goes unused. That is worth having
 * anyway rather than narrowing the type: there is no reason an administrator should not eventually
 * be able to ask what every failed attempt in the last hour has in common.</p>
 */
@Repository
public interface DeliveryAttemptRepository extends BaseJpaRepository<DeliveryAttempt> {

    /**
     * The history of one message, oldest attempt first.
     * @param notificationId the message
     * @return its attempts, in the order they were made
     */
    List<DeliveryAttempt> findByNotificationIdOrderByAttemptNoAsc(UUID notificationId);

    /**
     * The attempt a provider's status callback is about.
     *
     * <p>Keyed by both, because two providers may well hand out the same opaque string and only
     * together do they identify a call.</p>
     *
     * @param provider the provider that issued the id
     * @param providerMessageId the id it issued
     * @return the attempt, or empty if the callback is about something this service never sent
     */
    Optional<DeliveryAttempt> findByProviderAndProviderMessageId(String provider, String providerMessageId);
}
