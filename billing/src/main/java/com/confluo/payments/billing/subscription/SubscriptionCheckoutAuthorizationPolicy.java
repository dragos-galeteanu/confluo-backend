package com.confluo.payments.billing.subscription;

import com.confluo.common.filter.PaginationFilter;
import com.confluo.common.security.BaseAuthorizationPolicy;
import com.confluo.payments.billing.subscription.dto.SubscriptionCheckoutRequest;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Who may set up a subscription.
 *
 * <p>The subscriber for themselves, or an administrator or support agent for anybody. Nobody else:
 * a caller who could name an arbitrary subscriber could put a recurring charge on somebody else's
 * saved card.</p>
 *
 * <p>Separate from {@code SubscriptionAuthorizationPolicy}, which decides who may change or end an
 * existing subscription and has to load it to answer. This one decides from the request alone,
 * because there is nothing to load yet - the subscription does not exist until Stripe creates
 * it.</p>
 *
 * <p>The CRUD methods other than {@code canCreate} are all denials. {@link BaseAuthorizationPolicy}
 * is implemented for its role helpers and its {@code canCreate} shape; there are no CRUD routes on
 * a checkout, so the rest deny rather than pretending to have an opinion - and denying is the right
 * way to be wrong.</p>
 */
@Component("subscriptionCheckoutPolicy")
public class SubscriptionCheckoutAuthorizationPolicy
        implements BaseAuthorizationPolicy<SubscriptionCheckoutRequest, Void, UUID> {

    /**
     * Whether a caller may set up this subscription.
     *
     * @param authentication the caller
     * @param request what they want to subscribe to
     * @return true if they are the subscriber, or an administrator or support agent
     */
    @Override
    public boolean canCreate(Authentication authentication, SubscriptionCheckoutRequest request) {
        if (!isAuthenticated(authentication) || request == null) {
            return false;
        }
        if (isAdminOrSupport(authentication)) {
            return true;
        }
        return request.buyerFirebaseUid().equals(firebaseUidOf(authentication));
    }

    /**
     * The caller's Firebase uid.
     *
     * <p>The subject, not {@code SecurityUtils.extractUserId}: that returns a {@link UUID}, and a
     * Firebase uid is not one - handing it a token would get a parse failure rather than an
     * answer.</p>
     *
     * @param authentication the caller
     * @return their uid, or null if the principal is not a token
     */
    private String firebaseUidOf(Authentication authentication) {
        return authentication.getPrincipal() instanceof Jwt jwt ? jwt.getSubject() : null;
    }

    @Override
    public boolean canGetById(Authentication authentication, UUID id) {
        return false;
    }

    @Override
    public boolean canGetAll(Authentication authentication, PaginationFilter filter) {
        return false;
    }

    @Override
    public boolean canUpdate(Authentication authentication, Void request) {
        return false;
    }

    @Override
    public boolean canDelete(Authentication authentication, UUID id) {
        return false;
    }
}
