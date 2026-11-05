package com.confluo.payments.identity;

import com.confluo.common.security.BaseAuthorizationPolicy;
import com.confluo.payments.identity.dto.IdentityVerificationStartRequest;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Who may start an identity check, and who may see its outcome.
 *
 * <p>Themselves, or Admin or Support for anybody - the narrowest rule in this service, and the one
 * with the least room for argument. Starting a check on somebody's behalf sends them to a page
 * asking for a passport; seeing the outcome is seeing whether a named person failed an identity
 * check and why.</p>
 *
 * <p><strong>No listing route exists at all</strong>, which is deliberate rather than unfinished.
 * Everywhere else in this service has one: payments, discounts, the sellers awaiting requirements.
 * A list of who has failed identity verification is not an operational view worth the risk of
 * existing - it is answerable one person at a time, by somebody who already has a reason to ask
 * about that person.</p>
 *
 * <p>Note what this policy does <strong>not</strong> guard: the documents themselves. They never
 * reach this platform. The strongest protection here is that there is nothing to protect.</p>
 */
@Component("identityPolicy")
public class IdentityAuthorizationPolicy
        implements BaseAuthorizationPolicy<IdentityVerificationStartRequest, Void, UUID> {

    @Override
    public boolean canCreate(Authentication authentication, IdentityVerificationStartRequest request) {
        return request != null && isSelfOrAdmin(authentication, request.firebaseUid());
    }

    /**
     * Whether the caller may see somebody's verification state.
     *
     * @param authentication the caller
     * @param firebaseUid whose identity
     * @return true if it is their own, or they are an administrator or support agent
     */
    public boolean canAccessVerificationOf(Authentication authentication, String firebaseUid) {
        return isSelfOrAdmin(authentication, firebaseUid);
    }

    private boolean isSelfOrAdmin(Authentication authentication, String firebaseUid) {
        if (!isAuthenticated(authentication) || firebaseUid == null) {
            return false;
        }
        if (isAdminOrSupport(authentication)) {
            return true;
        }
        return authentication.getPrincipal() instanceof Jwt jwt
                && firebaseUid.equals(jwt.getSubject());
    }

    @Override
    public boolean canGetById(Authentication authentication, UUID id) {
        // Verification attempts are reached by the person, never by an id of this service's own.
        return false;
    }

    /**
     * Not a route, and not an omission. See the class javadoc: a list of who has failed an identity
     * check is not worth the risk of existing.
     *
     * @param authentication the caller
     * @param filter unused
     * @return false, always
     */
    @Override
    public boolean canGetAll(
            Authentication authentication, com.confluo.common.filter.PaginationFilter filter) {
        return false;
    }

    /**
     * Not a route. Everything about a verification is Stripe's answer; there is nothing here for
     * this platform to edit.
     *
     * @param authentication the caller
     * @param request unused
     * @return false, always
     */
    @Override
    public boolean canUpdate(Authentication authentication, Void request) {
        return false;
    }

    /**
     * Not a route. A verification attempt is the record of a decision, and deleting it would
     * destroy the only answer to why somebody was refused.
     *
     * @param authentication the caller
     * @param id unused
     * @return false, always
     */
    @Override
    public boolean canDelete(Authentication authentication, UUID id) {
        return false;
    }
}
