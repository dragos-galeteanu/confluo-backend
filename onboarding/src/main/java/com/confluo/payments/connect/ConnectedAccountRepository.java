package com.confluo.payments.connect;

import com.confluo.common.jpa.repository.BaseJpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;


/**
 * Connected accounts, by uid for this platform's own callers and by {@code acct_} id for Stripe's.
 */
@Repository
public interface ConnectedAccountRepository extends BaseJpaRepository<ConnectedAccount> {

    /**
     * The account belonging to a person.
     * @param firebaseUid the uid
     * @return the account, or empty if they have never started onboarding
     */
    Optional<ConnectedAccount> findByFirebaseUid(String firebaseUid);

    /**
     * The account an event is about.
     *
     * <p>Returns empty rather than failing for an account this service has no row for, which does
     * happen: an account created outside this platform, or one whose creation did not commit. The
     * webhook handler decides what to do about that, and dropping the event is not it.</p>
     *
     * @param stripeAccountId Stripe's {@code acct_} identifier
     * @return the account, or empty
     */
    Optional<ConnectedAccount> findByStripeAccountId(String stripeAccountId);

    /**
     * Whether somebody already has an account, without loading it.
     * @param firebaseUid the uid
     * @return true if one exists
     */
    boolean existsByFirebaseUid(String firebaseUid);

    /**
     * The accounts Stripe is still waiting on, which is what an onboarding reminder is sent from.
     *
     * <p>Expressed as "submitted but not yet payable" rather than by reading the derived state,
     * because the state is computed in Java and cannot be put in a query. The two must say the
     * same thing: this is {@code RESTRICTED} and the {@code NOT_STARTED} case separately.</p>
     *
     * @return the accounts that have submitted details and cannot yet be paid
     */
    List<ConnectedAccount> findByDetailsSubmittedTrueAndPayoutsEnabledFalse();
}
