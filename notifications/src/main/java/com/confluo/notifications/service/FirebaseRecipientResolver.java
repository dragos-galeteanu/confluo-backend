package com.confluo.notifications.service;

import com.confluo.notifications.entity.Recipient;
import com.confluo.notifications.entity.RecipientSource;
import com.google.firebase.auth.AuthErrorCode;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseAuthException;
import com.google.firebase.auth.UserRecord;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Locale;
import java.util.Optional;

/**
 * Last resort for a uid this service has never heard of: ask Firebase who it is.
 *
 * <p>The intended source of a recipient is an event from whichever service owns users. No such
 * service exists yet, and even once it does there will be people who registered before it started
 * publishing - so something has to be able to answer "who is this uid" from scratch, and Firebase
 * is the one place that already knows.</p>
 *
 * <p>What it returns is <strong>unsaved</strong>. Persisting is the caller's business, inside the
 * caller's transaction, which is what keeps this class free of a repository and of a transaction of
 * its own: it is a lookup, and a lookup that writes is a lookup nobody can use twice.</p>
 *
 * <p>Firebase knows an email and a phone number and nothing else this service cares about, so a row
 * written from here carries no language and no timezone, and {@link RecipientSource#FIREBASE_BACKFILL}
 * says why. It does not report whether a number was verified either, so the backfill does not claim
 * it was - a number set by an administrator and one proved by an SMS code look identical from
 * here, and the safe reading is the quiet one.</p>
 *
 * <p>{@link FirebaseAuth} is taken as an {@link ObjectProvider} because the bean is conditional:
 * {@code common-core} switches it off when {@code firebase.enabled} is false, which is how tests
 * and local runs start without credentials. A service running that way simply cannot backfill, and
 * says so once per attempt rather than failing to start.</p>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class FirebaseRecipientResolver {

    private final ObjectProvider<FirebaseAuth> firebaseAuth;

    /**
     * Builds a recipient from whatever Firebase knows about a uid.
     *
     * @param firebaseUid the uid to look up
     * @return the unsaved recipient, or empty when Firebase does not know the uid, is switched off,
     *         or knows the user but holds neither an address nor a number for them
     */
    public Optional<Recipient> lookUp(String firebaseUid) {
        FirebaseAuth auth = firebaseAuth.getIfAvailable();
        if (auth == null) {
            log.warn("Cannot backfill recipient: Firebase is not configured [uid={}]", firebaseUid);
            return Optional.empty();
        }

        UserRecord user;
        try {
            user = auth.getUser(firebaseUid);
        } catch (FirebaseAuthException e) {
            // A uid nobody has heard of is an ordinary answer, not a failure: it is what a caller
            // passing a stale or mistyped uid looks like. Anything else - a network problem, bad
            // credentials, a revoked key - is this service's problem and is logged as one.
            if (e.getAuthErrorCode() == AuthErrorCode.USER_NOT_FOUND) {
                log.debug("Firebase does not know uid {}", firebaseUid);
            } else {
                log.error("Firebase lookup failed [uid={}, code={}]", firebaseUid, e.getAuthErrorCode(), e);
            }
            return Optional.empty();
        }

        String email = user.getEmail() == null ? null : user.getEmail().toLowerCase(Locale.ROOT);
        String phone = user.getPhoneNumber();

        // Firebase can hold a user with neither, and the recipients table refuses one: a row
        // reachable by nothing would be a row every send has to discover is useless.
        if (email == null && phone == null) {
            log.warn("Firebase knows uid {} but holds no address or number for them", firebaseUid);
            return Optional.empty();
        }

        Recipient recipient = new Recipient();
        recipient.setFirebaseUid(firebaseUid);
        recipient.setEmail(email);
        recipient.setEmailVerified(user.isEmailVerified());
        recipient.setPhoneE164(phone);
        recipient.setPhoneVerified(false);
        recipient.setSource(RecipientSource.FIREBASE_BACKFILL);
        recipient.setLastSyncedAt(Instant.now());

        log.info("Recipient backfilled from Firebase [uid={}, hasEmail={}, hasPhone={}]",
                firebaseUid, email != null, phone != null);
        return Optional.of(recipient);
    }
}
