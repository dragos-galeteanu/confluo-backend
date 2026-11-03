package com.confluo.notifications.channel;

/**
 * How a message reaches someone.
 *
 * <p>Two of these are implemented. The other two are named here, and in the {@code CHECK}
 * constraints of every table that stores a channel, because the pipeline ahead of the senders -
 * suppression, preferences, templates, the dispatcher and its backoff - is the same whichever
 * channel a message takes, and the enum is what lets that code be written once. Adding one is a
 * {@code MessageSender} and a template, not a change to any of it.</p>
 *
 * <p>What a channel decides is only the transport. Whether a message may be sent at all is
 * {@link Category}'s business, and the two are stored separately for that reason: someone turning
 * off marketing SMS has said nothing about security email.</p>
 */
public enum Channel {

    /** email, sent as SMTP multipart with a text part beside the HTML one */
    EMAIL,

    /** a text message, through an SMS provider */
    SMS,

    /** not implemented: a notification held for the client to read in the application itself */
    IN_APP,

    /** not implemented: a push notification to a registered device */
    PUSH
}
