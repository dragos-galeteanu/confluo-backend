package com.confluo.notifications.entity;

/**
 * How a recipient's row came to say what it says.
 *
 * <p>Stored because the contact details here are a copy of someone else's truth, and the only
 * useful question about a copy that turns out to be wrong is where it came from. The four differ in
 * how much they can be trusted, in roughly this order.</p>
 */
public enum RecipientSource {

    /**
     * an event from the service that owns users. The intended source, and the only one that stays
     * current on its own.
     */
    IDENTITY_EVENT,

    /**
     * looked up in Firebase Auth because nothing here knew the uid. Authoritative about the email
     * and the phone number and silent about everything else, so a row written this way carries no
     * language and no preferences.
     */
    FIREBASE_BACKFILL,

    /** taken from the contact details on a send request, which is a caller's claim and no more */
    SEND_REQUEST,

    /** somebody set it by hand */
    ADMIN
}
