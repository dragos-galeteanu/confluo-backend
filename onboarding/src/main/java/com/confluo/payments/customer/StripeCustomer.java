package com.confluo.payments.customer;

import com.confluo.common.jpa.entity.BaseJpaEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.Setter;

/**
 * The mapping from this platform's identity to Stripe's, and nothing else.
 *
 * <p>Two identifiers is the whole entity on purpose. A customer's address, their default payment
 * method, their tax status - Stripe holds all of it, keeps it current, and is asked directly on the
 * rare path that needs it. What Stripe cannot answer is which of its customers belongs to which
 * Firebase uid, because that is this platform's own fact, so that is the one that is stored.</p>
 *
 * <p>Both columns are unique, and the second one matters as much as the first: two uids pointing at
 * one Stripe customer would bill one person for another's subscription.</p>
 */
@Getter
@Setter
@Entity
@Table(
        name = "stripe_customers",
        uniqueConstraints = {
                @UniqueConstraint(name = "uq_stripe_customers_firebase_uid", columnNames = "firebase_uid"),
                @UniqueConstraint(name = "uq_stripe_customers_stripe_id", columnNames = "stripe_customer_id")
        })
public class StripeCustomer extends BaseJpaEntity {

    /** the Firebase uid, which is the identity every other service already speaks in */
    @Column(name = "firebase_uid", nullable = false, length = 128)
    private String firebaseUid;

    /** Stripe's own {@code cus_} identifier */
    @Column(name = "stripe_customer_id", nullable = false, length = 255)
    private String stripeCustomerId;
}
