package com.confluo.notifications.security;

import com.confluo.common.filter.PaginationFilter;
import com.confluo.common.security.BaseAuthorizationPolicy;
import com.confluo.notifications.dto.recipient.RecipientCreateRequest;
import com.confluo.notifications.dto.recipient.RecipientUpdateRequest;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Who may do what with recipients.
 *
 * <p>Nothing here is public, unlike the platform's reference data. A recipient row is somebody's
 * email address and phone number, and the listing is every address this service holds.</p>
 *
 * <p>Reading is open to Support as well as Admin, because "did this person get the message, and
 * where did we send it" is a support question and answering it should not need an administrator.
 * Writing is Admin only, including disabling: {@code canDisable} would otherwise admit Support by
 * default, and disabling a recipient stops every declinable message to them - a bigger lever than
 * it looks.</p>
 */
@Component("recipientPolicy")
public class RecipientAuthorizationPolicy implements BaseAuthorizationPolicy<RecipientCreateRequest, RecipientUpdateRequest, UUID> {

    @Override
    public boolean canCreate(Authentication authentication, RecipientCreateRequest request) {
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
    public boolean canUpdate(Authentication authentication, RecipientUpdateRequest request) {
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

    /**
     * Whether the caller may look a recipient up by their Firebase uid.
     *
     * <p>Not part of {@link BaseAuthorizationPolicy}, which is keyed to a {@code UUID} id, and the
     * same rule as reading one by id: it is the same row reached by its other name.</p>
     *
     * @param authentication the authentication object
     * @param firebaseUid the uid being looked up
     * @return true if the caller may read it
     */
    public boolean canGetByFirebaseUid(Authentication authentication, String firebaseUid) {
        return isAdminOrSupport(authentication);
    }
}
