package com.confluo.payments.connect;

import com.confluo.common.security.BaseAuthorizationPolicy;
import com.confluo.payments.connect.dto.ConnectAccountCreateRequest;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Who may onboard, and who may look at a seller's account.
 *
 * <p>A seller for themselves, or Admin or Support for anybody. The self case is the ordinary one -
 * onboarding is something a seller does - and the Support case exists because "why can't I get paid
 * yet" is the single most common question this part of the platform produces, and it can only be
 * answered by looking at the requirements Stripe is still waiting on.</p>
 *
 * <p><strong>Creating an account for somebody else is the one worth being careful about.</strong>
 * A connected account is where money ends up, so a caller who could name an arbitrary uid could
 * point a seller's payouts at an account they control. Hence the same rule as a checkout: yourself,
 * or an administrator.</p>
 *
 * <p>The onboarding link is narrower still in effect, though the rule is the same: a link is a way
 * into Stripe's account settings for that seller, so it must only ever be handed to them. That is
 * why it is returned to the caller for an immediate redirect and never emailed or stored - see
 * {@code OnboardingLinkResponse}.</p>
 */
@Component("connectPolicy")
public class ConnectAuthorizationPolicy
        implements BaseAuthorizationPolicy<ConnectAccountCreateRequest, Void, UUID> {

    @Override
    public boolean canCreate(Authentication authentication, ConnectAccountCreateRequest request) {
        return request != null && isSelfOrAdmin(authentication, request.firebaseUid());
    }

    /**
     * Whether the caller may see a seller's account, or ask for an onboarding link for them.
     *
     * @param authentication the caller
     * @param firebaseUid whose account
     * @return true if it is their own, or they are an administrator or support agent
     */
    public boolean canAccessAccountOf(Authentication authentication, String firebaseUid) {
        return isSelfOrAdmin(authentication, firebaseUid);
    }

    /**
     * Whether the caller may list the sellers Stripe is still waiting on.
     *
     * <p>Admin or Support only. It is a list of everybody whose onboarding is incomplete, which is
     * an operational view rather than anything a seller needs.</p>
     *
     * @param authentication the caller
     * @return true if they are an administrator or support agent
     */
    public boolean canListAwaitingRequirements(Authentication authentication) {
        return isAdminOrSupport(authentication);
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
        // A connected account is reached by the seller's uid, never by this service's own id:
        // a caller holding one of these ids could only have got it from a response about an
        // account they could already see, so the route would add nothing but a second rule.
        return false;
    }

    @Override
    public boolean canGetAll(Authentication authentication, com.confluo.common.filter.PaginationFilter filter) {
        return isAdminOrSupport(authentication);
    }

    /**
     * Not a route. Everything about a connected account is Stripe's to change, through its own
     * hosted pages - there is nothing here for this platform to update.
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
     * Not a route. A connected account cannot be deleted while it holds a balance or has taken
     * payments, which is Stripe's rule and a sound one - and detaching a seller from their payout
     * history is not something this platform should offer.
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
