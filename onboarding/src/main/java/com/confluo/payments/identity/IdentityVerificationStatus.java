package com.confluo.payments.identity;

import com.confluo.payments.connect.ConnectedAccountState;

/**
 * Where a Stripe Identity verification session has got to.
 *
 * <p>Deliberately separate from a connected account's {@link ConnectedAccountState}. Identity
 * checks a person's documents; Connect decides whether an account may be paid. Conflating the two
 * gives you a user who has passed identity verification and still cannot receive money, and no way
 * to tell which of the two is the problem.</p>
 */
public enum IdentityVerificationStatus {

    /** waiting on the person: no documents yet, or the ones given were not usable */
    REQUIRES_INPUT,

    /** documents submitted, Stripe still checking them */
    PROCESSING,

    /** Stripe is satisfied the person is who they claim to be */
    VERIFIED,

    /** abandoned, by the person or by this service */
    CANCELED
}
