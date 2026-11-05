package com.confluo.payments.identity;

import com.confluo.common.jpa.repository.BaseJpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;


/**
 * Identity sessions, by Stripe's session id and by the person they are about.
 */
@Repository
public interface IdentityVerificationRepository extends BaseJpaRepository<IdentityVerification> {

    /**
     * The session an event is about.
     * @param stripeVerificationSessionId Stripe's {@code vs_} identifier
     * @return the session, or empty
     */
    Optional<IdentityVerification> findByStripeVerificationSessionId(String stripeVerificationSessionId);

    /**
     * Somebody's most recent attempt, which is the one that says where they stand.
     *
     * <p>Ordered by creation rather than by status, because "have they passed?" is answered by the
     * latest attempt and not by whether any attempt ever succeeded - a verification can be
     * superseded.</p>
     *
     * @param firebaseUid the uid
     * @return their newest session, or empty if they have never attempted one
     */
    Optional<IdentityVerification> findFirstByFirebaseUidOrderByCreatedAtDesc(String firebaseUid);
}
