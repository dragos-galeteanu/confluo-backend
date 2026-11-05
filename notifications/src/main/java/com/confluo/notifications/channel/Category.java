package com.confluo.notifications.channel;

/**
 * What kind of message this is, and therefore whether someone may decline it.
 *
 * <p>This is the only thing standing between a preference and a password reset nobody receives.
 * {@link #TRANSACTIONAL} cannot be switched off - not by a preference, not by an unsubscribe link -
 * and that rule is enforced by a {@code CHECK} constraint on {@code notification_preferences} as
 * well as by the pipeline, because a hole here is invisible: nobody complains about the message
 * they did not get.</p>
 *
 * <p>The order matters in one direction only: these are listed from the least declinable to the
 * most, which is the order in which the pipeline's checks get stricter.</p>
 */
public enum Category {

    /**
     * Something the person is in the middle of doing: a password reset, a verification code, a
     * receipt. Not declinable. Sending one is part of the service working at all, and a preference
     * that could stop it would be a preference to break the account.
     */
    TRANSACTIONAL,

    /**
     * Something about their account they did not directly ask for right now: a sign-in from a new
     * device, a change of email, an expiring card. Declinable, but on by default - the cost of not
     * receiving one is usually higher than the annoyance of receiving it.
     */
    ACCOUNT,

    /**
     * Anything promotional. Declinable and <strong>off</strong> by default: opt-in is the lawful
     * default in the EU, and it is also the one that fails safe when a preference row is missing.
     */
    MARKETING;

    /**
     * Whether this is sent to someone who has said nothing about it.
     *
     * <p>Most combinations of a recipient, a channel and a category have no stored preference, and
     * that is not an omission - writing every one out for every recipient would be a row count with
     * no information in it, and would make changing a default a migration. So the answer has to come
     * from somewhere, and it comes from here, next to the categories themselves rather than from a
     * configuration file where it could differ per environment. What someone is sent when they have
     * not spoken is a product decision, not a deployment one.</p>
     *
     * @return true unless this is {@link #MARKETING}
     */
    public boolean enabledByDefault() {
        return this != MARKETING;
    }

    /**
     * Whether a preference may switch this off at all.
     * @return false only for {@link #TRANSACTIONAL}
     */
    public boolean declinable() {
        return this != TRANSACTIONAL;
    }
}
