package com.confluo.payments.connect;

import com.confluo.common.jpa.entity.BaseJpaEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Somebody who gets paid, and what Stripe currently permits them.
 *
 * <p>Every field below the two identifiers is <strong>Stripe's answer</strong>, written by the
 * {@code account.updated} webhook and by nothing else. This service never decides that somebody is
 * verified; it records that Stripe has, which is the only form of that statement anyone can act
 * on. A setter called from anywhere but a webhook handler is a bug.</p>
 *
 * <p>There is no state column, and {@link #getState()} is why: the state is a function of four
 * fields that arrive together, so storing it would be a fifth field that has to agree with the
 * other four - and it is always the derived copy that goes stale.</p>
 *
 * <p>{@link #lastAccountEventAt} is what makes this row safe against the order webhooks arrive in.
 * Stripe guarantees none, so a handler compares against it and ignores anything older; without
 * that, a delayed event can resurrect a state Stripe has already moved on from.</p>
 */
@Getter
@Setter
@Entity
@Table(
        name = "connected_accounts",
        uniqueConstraints = {
                @UniqueConstraint(name = "uq_connected_accounts_firebase_uid", columnNames = "firebase_uid"),
                @UniqueConstraint(name = "uq_connected_accounts_stripe_id", columnNames = "stripe_account_id")
        })
public class ConnectedAccount extends BaseJpaEntity {

    /** whose account it is */
    @Column(name = "firebase_uid", nullable = false, length = 128)
    private String firebaseUid;

    /** Stripe's own {@code acct_} identifier */
    @Column(name = "stripe_account_id", nullable = false, length = 255)
    private String stripeAccountId;

    /**
     * The ISO-3166 country the account was created in, uppercase. Set once and never again: it
     * decides what Stripe requires of the person and what currencies they can be paid in, and
     * Stripe does not allow it to change - somebody who moves country needs a new account.
     */
    @Column(name = "country", nullable = false, length = 2)
    private String country;

    /** the currency Stripe settles this account in, uppercase */
    @Column(name = "default_currency", nullable = false, length = 3)
    private String defaultCurrency;

    /** whether onboarding was completed. False means Stripe has nothing to verify yet */
    @Column(name = "details_submitted", nullable = false)
    private boolean detailsSubmitted = false;

    /** whether this account may take payments */
    @Column(name = "charges_enabled", nullable = false)
    private boolean chargesEnabled = false;

    /** whether this account may be paid out */
    @Column(name = "payouts_enabled", nullable = false)
    private boolean payoutsEnabled = false;

    /**
     * Stripe's reason for withholding charges or payouts, or null if it is not. Free text because
     * Stripe adds reasons; everything meaning rejected begins {@code rejected.}, which is what
     * {@link ConnectedAccountState#of} keys off rather than a list needing a release to extend.
     */
    @Column(name = "disabled_reason", length = 64)
    private String disabledReason;

    /**
     * What Stripe still wants, as it names it - e.g. {@code individual.verification.document}.
     * A document rather than columns because the list is Stripe's to shape, and the only thing
     * done with it is showing the person what is outstanding.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "requirements_currently_due", nullable = false, columnDefinition = "jsonb")
    private List<String> requirementsCurrentlyDue = new ArrayList<>();

    /** the same, for requirements whose deadline has passed and which are now holding payouts */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "requirements_past_due", nullable = false, columnDefinition = "jsonb")
    private List<String> requirementsPastDue = new ArrayList<>();

    /** when onboarding was first completed, or null if it never was */
    @Column(name = "onboarding_completed_at")
    private Instant onboardingCompletedAt;

    /**
     * The timestamp of the most recent account event applied to this row, which is not
     * {@code updatedAt}: that moves when anything changes, and this answers the narrower question
     * of how recent Stripe's version of these facts is.
     *
     * <p><strong>Null until Stripe has said something.</strong> A row is created before any
     * webhook arrives, and filling this in with the local clock at that point would make the first
     * {@code account.updated} look older than the row whenever Stripe's clock is a moment behind -
     * dropping the one event that enables the seller.</p>
     */
    @Column(name = "last_account_event_at")
    private Instant lastAccountEventAt;

    /**
     * What this account can currently do, as one answer.
     *
     * <p>Derived on every call and stored nowhere. Hibernate maps fields rather than properties
     * here - {@code BaseJpaEntity} annotates its own - so this is invisible to it either way;
     * {@code @Transient} says so to a reader.</p>
     *
     * @return the state the four Stripe fields describe
     */
    @Transient
    public ConnectedAccountState getState() {
        return ConnectedAccountState.of(detailsSubmitted, chargesEnabled, payoutsEnabled, disabledReason);
    }
}
