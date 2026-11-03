package com.confluo.notifications.entity;

/**
 * Where a notification has got to.
 *
 * <p>Only {@link #PENDING} is claimed by the dispatcher, which is why the index it reads is partial
 * on exactly that value. A row leaves this set of states for good once it reaches {@link #SENT} or
 * further; {@link #FAILED}, {@link #SUPPRESSED} and {@link #CANCELLED} are all terminal, and are
 * three states rather than one because the question asked of a message that never arrived is
 * always "why" and the answers are not alike.</p>
 */
public enum NotificationStatus {

    /** rendered, committed, and waiting for the dispatcher to claim it */
    PENDING,

    /**
     * claimed, with a provider call in flight. A row left here is how a process that died mid-send
     * is recognised, which is why the dispatcher's sweep looks at age rather than only at status.
     */
    SENDING,

    /** the provider accepted it. For email over SMTP this is as far as the truth goes */
    SENT,

    /** a provider's status callback said it arrived */
    DELIVERED,

    /** every attempt was used up, or the provider refused it permanently */
    FAILED,

    /**
     * never attempted, because a preference or the suppression list said not to. Recorded rather
     * than dropped: "we decided not to send this" is an answer, and silence is not.
     */
    SUPPRESSED,

    /** withdrawn before it was sent */
    CANCELLED
}
