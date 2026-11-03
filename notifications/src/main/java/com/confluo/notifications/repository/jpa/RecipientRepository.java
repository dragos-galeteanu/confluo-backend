package com.confluo.notifications.repository.jpa;

import com.confluo.common.jpa.repository.BaseJpaRepository;
import com.confluo.notifications.entity.Recipient;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * Recipients, keyed by id and by the Firebase uid everything else speaks in.
 *
 * <p>The two lookups by destination exist for the inbound direction: a provider's callback names an
 * address or a number and nothing else, so finding whose it was is the only way to attribute a
 * bounce or a STOP.</p>
 */
@Repository
public interface RecipientRepository extends BaseJpaRepository<Recipient> {

    /**
     * The recipient for a Firebase uid.
     * @param firebaseUid the uid
     * @return the recipient, or empty if this service has never heard of them
     */
    Optional<Recipient> findByFirebaseUid(String firebaseUid);

    /**
     * Whether a uid is already known, without loading the row.
     * @param firebaseUid the uid
     * @return true if a recipient exists for it
     */
    boolean existsByFirebaseUid(String firebaseUid);

    /**
     * The recipient holding an email address.
     *
     * <p>Exact, and the caller lowercases first. An {@code IgnoreCase} derived method would read
     * better and cost more than it looks: it compiles to {@code upper(email) = upper(?)}, which
     * cannot use {@code ix_recipients_email} and so scans the table on a path a provider's callback
     * takes. The column is constrained to lowercase instead, by
     * {@code ck_recipients_email_lowercase}, which makes exact matching correct rather than
     * merely fast.</p>
     *
     * @param email the address, lowercased
     * @return the recipient, or empty if nobody here holds it
     */
    Optional<Recipient> findByEmail(String email);

    /**
     * The recipient holding a phone number.
     * @param phoneE164 the number in E.164, which has no case to normalise
     * @return the recipient, or empty if nobody here holds it
     */
    Optional<Recipient> findByPhoneE164(String phoneE164);
}
