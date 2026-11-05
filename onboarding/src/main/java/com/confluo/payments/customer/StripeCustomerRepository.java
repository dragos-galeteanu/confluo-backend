package com.confluo.payments.customer;

import com.confluo.common.jpa.repository.BaseJpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;


/**
 * The identity mapping, looked up from either side.
 *
 * <p>Both directions are needed and for different callers. A checkout knows the uid and needs the
 * Stripe customer; a webhook knows only the {@code cus_} id and needs the uid, because nothing in
 * Stripe's payload says who that is in this platform's terms.</p>
 */
@Repository
public interface StripeCustomerRepository extends BaseJpaRepository<StripeCustomer> {

    /**
     * The mapping for a Firebase uid.
     * @param firebaseUid the uid
     * @return the mapping, or empty if this person has never been given a Stripe customer
     */
    Optional<StripeCustomer> findByFirebaseUid(String firebaseUid);

    /**
     * The mapping for a Stripe customer, which is how a webhook finds whose it is.
     * @param stripeCustomerId Stripe's {@code cus_} identifier
     * @return the mapping, or empty if this service does not know that customer
     */
    Optional<StripeCustomer> findByStripeCustomerId(String stripeCustomerId);

    /**
     * Whether a uid already has a Stripe customer, without loading the row.
     * @param firebaseUid the uid
     * @return true if one exists
     */
    boolean existsByFirebaseUid(String firebaseUid);
}
