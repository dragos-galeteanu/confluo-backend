package com.confluo.notifications.entity;

/**
 * Why a destination is on the suppression list.
 *
 * <p>Worth keeping apart rather than collapsing into a flag, because the two halves behave
 * differently: the first three are the person's decision and only they can undo them, while the
 * rest are facts about the address or number and are undone by it starting to work again.</p>
 */
public enum SuppressionReason {

    /** the person used an unsubscribe link */
    UNSUBSCRIBED,

    /** the person answered STOP to a text message */
    STOP,

    /** the person marked a message as spam, which a provider reports as a complaint */
    COMPLAINT,

    /**
     * the address does not exist, or the mailbox is permanently closed. A soft bounce - a full
     * mailbox, a server that was briefly down - is a retry and never reaches this list.
     */
    HARD_BOUNCE,

    /** the provider refused the destination outright as unroutable or malformed */
    INVALID,

    /** somebody put it here by hand */
    MANUAL
}
