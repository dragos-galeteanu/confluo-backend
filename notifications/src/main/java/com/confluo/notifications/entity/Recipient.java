package com.confluo.notifications.entity;

import com.confluo.common.jpa.entity.BaseJpaEntity;
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
 * Someone this service can reach, and how.
 *
 * <p>The contact details are a <strong>copy</strong>. They are kept current by consuming the events
 * of whichever service owns users, which is the point: a send must not wait on that service being
 * up, and contacts carried inside every event contract would go stale sitting in an outbox.
 * {@link #source} says how this row came to say what it says, because the only useful question
 * about a copy that turns out to be wrong is where it came from.</p>
 *
 * <p>The constraints are named to match the ones
 * {@code V1__create_notification_tables.sql} creates, so the entity and the migration can be read
 * against each other. Declaring them here does not create them - the schema is Flyway's and
 * Hibernate runs with {@code ddl-auto: validate} - but it keeps the entity honest about the shape
 * of the table it is mapped to. Two of them say things this class cannot: that a recipient
 * reachable by neither an address nor a number is refused, and that a phone number is stored in
 * E.164 or not at all.</p>
 */
@Getter
@Setter
@Entity
@Table(
        name = "recipients",
        uniqueConstraints = @UniqueConstraint(name = "uq_recipients_firebase_uid", columnNames = "firebase_uid"))
public class Recipient extends BaseJpaEntity {

    /** the Firebase uid, which is the identity every other service already speaks in */
    @Column(name = "firebase_uid", nullable = false, length = 128)
    private String firebaseUid;

    /** where email goes, or null if this person is reachable only by text */
    @Column(name = "email", length = 320)
    private String email;

    /**
     * Whether the address has been proved to belong to them. Nothing here acts on it yet; it is
     * worth carrying because sending to an unverified address is how a typo becomes someone else's
     * spam complaint.
     */
    @Column(name = "email_verified", nullable = false)
    private boolean emailVerified = false;

    /** the phone number in E.164, e.g. +40721234567, or null */
    @Column(name = "phone_e164", length = 20)
    private String phoneE164;

    /** whether the number has been proved to belong to them */
    @Column(name = "phone_verified", nullable = false)
    private boolean phoneVerified = false;

    /**
     * The language to render in, as the platform spells its language codes. No foreign key: that
     * table lives in another database. Null means the service's default locale.
     */
    @Column(name = "language_code", length = 8)
    private String languageCode;

    /**
     * The IANA zone, e.g. {@code Europe/Bucharest}. Nothing reads it yet - holding a message until
     * someone's morning would - and it is here so that adding that is a change to a service rather
     * than a migration.
     */
    @Column(name = "timezone", length = 64)
    private String timezone;

    /** how this row came to say what it says */
    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false, length = 32)
    private RecipientSource source;

    /**
     * When the contact details were last written, which is not {@code updatedAt}: that moves when
     * anything on the row changes, and this answers the narrower question of how old the copy is.
     */
    @Column(name = "last_synced_at", nullable = false)
    private Instant lastSyncedAt;
}
