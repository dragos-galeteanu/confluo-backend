package com.confluo.notifications.security;

import com.confluo.common.filter.PaginationFilter;
import com.confluo.common.security.BaseAuthorizationPolicy;
import com.confluo.notifications.dto.preference.NotificationPreferencesUpdateRequest;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Who may read and change what a recipient has agreed to receive.
 *
 * <p>Reading is Admin or Support - "why did they not get it" is answered here more often than
 * anywhere else. Changing is Admin only, because changing someone else's preferences on their
 * behalf is an unusual thing to do and should be a traceable one.</p>
 *
 * <p>The methods keyed to a {@code UUID} are not reachable from the controller, which addresses
 * preferences by the recipient's Firebase uid rather than by an id of their own. They are
 * implemented because the interface requires them and they state the same rule, which is the
 * convention the platform's own policies follow - authorization is decided in the policy and
 * nowhere else, so an unused method that disagreed with the used ones would be a trap.</p>
 *
 * <p>Recipients managing their <em>own</em> preferences is not here yet. When it arrives it is a
 * method on this class comparing the uid in the path with the one in the token, plus a route; the
 * unsubscribe link is a different thing again, since whoever clicks it holds a signed token and no
 * Firebase session at all.</p>
 */
@Component("preferencePolicy")
public class NotificationPreferenceAuthorizationPolicy implements BaseAuthorizationPolicy<NotificationPreferencesUpdateRequest, NotificationPreferencesUpdateRequest, UUID> {

    /**
     * Whether the caller may read a recipient's preferences.
     * @param authentication the authentication object
     * @param firebaseUid the recipient
     * @return true if the caller may read them
     */
    public boolean canList(Authentication authentication, String firebaseUid) {
        return isAdminOrSupport(authentication);
    }

    /**
     * Whether the caller may change a recipient's preferences.
     * @param authentication the authentication object
     * @param firebaseUid the recipient
     * @param request the settings being applied
     * @return true if the caller may change them
     */
    public boolean canApply(Authentication authentication, String firebaseUid, NotificationPreferencesUpdateRequest request) {
        return isAdmin(authentication);
    }

    @Override
    public boolean canCreate(Authentication authentication, NotificationPreferencesUpdateRequest request) {
        return isAdmin(authentication);
    }

    @Override
    public boolean canGetById(Authentication authentication, UUID id) {
        return isAdminOrSupport(authentication);
    }

    @Override
    public boolean canGetAll(Authentication authentication, PaginationFilter filter) {
        return isAdminOrSupport(authentication);
    }

    @Override
    public boolean canUpdate(Authentication authentication, NotificationPreferencesUpdateRequest request) {
        return isAdmin(authentication);
    }

    @Override
    public boolean canDelete(Authentication authentication, UUID id) {
        return isAdmin(authentication);
    }

    @Override
    public boolean canDisable(Authentication authentication) {
        return isAdmin(authentication);
    }
}
