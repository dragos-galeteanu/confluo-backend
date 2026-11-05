package com.confluo.payments.marketplace;

import com.confluo.common.filter.PaginationFilter;
import com.confluo.common.security.BaseAuthorizationPolicy;
import com.confluo.payments.marketplace.dto.OrderCheckoutRequest;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Who may set up a payment.
 *
 * <p>The buyer for themselves, or an administrator or support agent for anybody. Nobody else: a
 * caller who could name an arbitrary buyer could point a payment at somebody else's saved card.
 * {@code SubscriptionCheckoutAuthorizationPolicy} says the same thing about a subscription, in
 * billing's package, because the two requests have nothing in common but the rule.</p>
 *
 * <p><strong>There is no service caller, and there is not meant to be.</strong> Interservice
 * communication on this platform is AMQP, so a service that wants something from payments publishes
 * a fact rather than calling a route - which settles what this policy was once left open against. A
 * checkout cannot work that way, because it answers with a URL the payer has to be redirected to
 * and there is nothing to redirect from a message; so the request comes from the buyer's own client
 * with the buyer's own token, which is exactly what this allows. The service that owns orders is
 * not in that path at all: it learns a payment succeeded from {@code payment.succeeded}.</p>
 *
 * <p>The CRUD methods are all denials. {@link BaseAuthorizationPolicy} is implemented for its role
 * helpers and its {@code canCreate} shape; there are no CRUD routes on a checkout, so the rest deny
 * rather than pretending to have an opinion - and denying is the right way to be wrong.</p>
 */
@Component("checkoutPolicy")
public class CheckoutAuthorizationPolicy
        implements BaseAuthorizationPolicy<OrderCheckoutRequest, Void, UUID> {

    /**
     * Whether a caller may set up this payment.
     *
     * @param authentication the caller
     * @param request what they want to charge for
     * @return true if they are the buyer, or an administrator or support agent
     */
    @Override
    public boolean canCreate(Authentication authentication, OrderCheckoutRequest request) {
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
