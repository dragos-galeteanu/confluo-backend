package com.confluo.payments.identity;

import com.confluo.common.jpa.entity.BaseJpaEntity;
import com.confluo.payments.connect.ConnectedAccount;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;


/**
 * One Stripe Identity session: an attempt to prove somebody is who they say they are.
 *
 * <p>Deliberately not part of {@link ConnectedAccount}. Identity checks a person's documents;
 * Connect decides whether an account may be paid. Conflating them produces a user who has passed
 * identity verification and still cannot receive money, with no way to tell which half is the
 * problem.</p>
 *
 * <p>One row per attempt, not per person: a failed check is retried, and what failed the first time
 * is the only explanation of what to tell them. The uid is therefore indexed rather than unique.</p>
 */
@Getter
@Setter
@Entity
@Table(
        name = "identity_verifications",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_identity_verifications_session",
                columnNames = "stripe_verification_session_id"))
public class IdentityVerification extends BaseJpaEntity {

    /** whose identity is being checked */
    @Column(name = "firebase_uid", nullable = false, length = 128)
    private String firebaseUid;

    /** Stripe's own {@code vs_} identifier */
    @Column(name = "stripe_verification_session_id", nullable = false, length = 255)
    private String stripeVerificationSessionId;

    /** where the check has got to */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 24)
    private IdentityVerificationStatus status;

    /** when Stripe became satisfied, or null if it has not */
    @Column(name = "verified_at")
    private Instant verifiedAt;

    /** Stripe's code for why the last attempt was not enough, e.g. {@code document_unverified_other} */
    @Column(name = "last_error_code", length = 64)
    private String lastErrorCode;

    /** Stripe's own wording of it, which is what the person is shown */
    @Column(name = "last_error_reason", columnDefinition = "TEXT")
    private String lastErrorReason;
}
