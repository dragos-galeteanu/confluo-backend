package com.confluo.notifications.entity;

/**
 * What came of one call to a provider.
 *
 * <p>Distinct from {@link NotificationStatus} on purpose. That one is the state of a message; this
 * is the verdict on a single attempt, and a message can have several of these before it settles -
 * two {@link #FAILED} attempts and then an {@link #ACCEPTED} one is an ordinary history.</p>
 *
 * <p>The first two are decided while the call is being made. The rest arrive later, through a
 * provider's status callback, which is why a row's status is updated after it is written - and why
 * there is an index on the provider's own message id to find it again.</p>
 */
public enum DeliveryStatus {

    /** the provider took it. Not a statement that anyone received it */
    ACCEPTED,

    /**
     * the provider refused it, permanently. The destination is usually suppressed as well, because
     * a permanent refusal is about the address rather than about this message.
     */
    REJECTED,

    /** the call did not complete: a timeout, a connection refused, a provider's own 5xx */
    FAILED,

    /** a callback said it arrived */
    DELIVERED,

    /** a callback said the address rejected it */
    BOUNCED,

    /** a callback said it could not be delivered - an unreachable handset, a disconnected number */
    UNDELIVERED
}
