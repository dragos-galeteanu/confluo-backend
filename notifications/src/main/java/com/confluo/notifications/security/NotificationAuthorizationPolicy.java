package com.confluo.notifications.security;

import com.confluo.common.filter.PaginationFilter;
import com.confluo.common.security.BaseAuthorizationPolicy;
import com.confluo.notifications.dto.notification.NotificationSendRequest;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Who may send a message, and who may read what was sent.
 *
 * <p>Sending is Admin only <strong>for now, and that is the open question</strong>, not the answer.
 * The callers this route exists for are other services, which hold no Firebase user token to
 * forward - so until that is decided, the only callers who can reach it are administrators. The
 * decision is a mechanism, not a rule: a shared service token, mutual TLS, a service identity in
 * Firebase, or a route reachable only inside the cluster. Whichever it is, it widens
 * {@link #canCreate} and changes nothing else.</p>
 *
 * <p>Reading the log is Admin or Support, because "did this person get the message, and where did
 * we send it" is the most common support question there is. The log carries addresses and phone
 * numbers, which is why it goes no wider than that.</p>
 *
 * <p>Reading a message's rendered content is separate and narrower than reading the log: the log
 * says a message went, while the content is the message itself, including whatever a caller passed
 * into it.</p>
 */
@Component("notificationPolicy")
public class NotificationAuthorizationPolicy implements BaseAuthorizationPolicy<NotificationSendRequest, NotificationSendRequest, UUID> {

    /**
     * Whether the caller may ask for a message to be sent.
     *
     * <p>Asking for a message is creating one, so this is the interface's own method rather than a
     * {@code canSend} of its own - every other controller in the platform guards its write with
     * {@code canCreate}, and a second name for one rule is a second place for it to drift.</p>
     *
     * <p>An ad-hoc send - one naming a raw destination rather than a person - cannot consult
     * preferences, because there is nobody whose preferences they would be. It is allowed here
     * because the suppression list still applies and the route is an administrator's, but it is
     * the reason this method should not be widened without separating the two shapes.</p>
     *
     * @param authentication the authentication object
     * @param request what would be sent
     * @return true if the caller may send it
     */
    @Override
    public boolean canCreate(Authentication authentication, NotificationSendRequest request) {
        return isAdmin(authentication);
    }

    /**
     * Whether the caller may read the words a message was sent with.
     *
     * <p>Not folded into {@link #canGetById}, although both answer the same today. The log says a
     * message went and where to; the content is the message itself, including whatever a caller
     * passed into it - two different questions, and only one of them has an interface method. The
     * narrower one is the one that should be able to tighten without the other moving.</p>
     *
     * @param authentication the authentication object
     * @param id the message
     * @return true if the caller may read them
     */
    public boolean canReadContent(Authentication authentication, UUID id) {
        return isAdminOrSupport(authentication);
    }

    @Override
    public boolean canGetById(Authentication authentication, UUID id) {
        return isAdminOrSupport(authentication);
    }

    @Override
    public boolean canGetAll(Authentication authentication, PaginationFilter filter) {
        return isAdminOrSupport(authentication);
    }

    /**
     * Nothing may be updated.
     *
     * <p>A message that has been decided on is a record of a decision, and a sent one is a record
     * of an event. Neither is editable, which is why there is no update route for the policy to
     * guard - this states the same thing in the one place authorization is decided.</p>
     *
     * @param authentication the authentication object
     * @param request ignored
     * @return false, always
     */
    @Override
    public boolean canUpdate(Authentication authentication, NotificationSendRequest request) {
        return false;
    }

    @Override
    public boolean canDelete(Authentication authentication, UUID id) {
        return isAdmin(authentication);
    }

    @Override
    public boolean canDisable(Authentication authentication) {
        return false;
    }
}
