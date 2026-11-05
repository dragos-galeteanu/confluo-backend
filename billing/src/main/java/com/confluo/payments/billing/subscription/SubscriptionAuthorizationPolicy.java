package com.confluo.payments.billing.subscription;

import com.confluo.common.security.BaseAuthorizationPolicy;
import com.confluo.payments.billing.subscription.dto.SubscriptionChangeRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;


/**
 * Who may change or end a subscription.
 *
 * <p>The subscriber, or Admin or Support. Self-service was the choice made when this was scoped,
 * and it is the right one: somebody who cannot cancel without contacting support will dispute the
 * charge instead, which costs more than the subscription.</p>
 *
 * <p><strong>This policy has to load the subscription to answer.</strong> Every other policy in
 * this service decides from the request alone - a checkout names its buyer, a Connect route names
 * its seller - but a subscription is addressed by an id of this service's own, and whose it is can
 * only be learned by looking. That is a query inside an authorization check, which is worth being
 * deliberate about: it runs on every write to a subscription, and it is the only correct way to
 * answer the question.</p>
 */
@Component("subscriptionPolicy")
@RequiredArgsConstructor
public class SubscriptionAuthorizationPolicy
        implements BaseAuthorizationPolicy<SubscriptionChangeRequest, SubscriptionChangeRequest, UUID> {

    private final SubscriptionRepository subscriptions;

    /**
     * Whether the caller may act on a particular subscription.
     *
     * @param authentication the caller
     * @param id which subscription
     * @return true if it is theirs, or they are an administrator or support agent
     */
    public boolean canManage(Authentication authentication, UUID id) {
        if (!isAuthenticated(authentication) || id == null) {
            return false;
        }
        if (isAdminOrSupport(authentication)) {
            return true;
        }
        // A subscription that does not exist is refused rather than reported: answering "no such
        // subscription" to somebody who may not see it would let them learn which ids are real.
        Optional<Subscription> subscription = subscriptions.findById(id);
        return subscription.isPresent()
                && authentication.getPrincipal() instanceof Jwt jwt
                && subscription.get().getFirebaseUid().equals(jwt.getSubject());
    }

    /**
     * Whether the caller may list somebody's subscriptions.
     *
     * @param authentication the caller
     * @param firebaseUid whose subscriptions
     * @return true if they are their own, or the caller is an administrator or support agent
     */
    public boolean canListFor(Authentication authentication, String firebaseUid) {
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
        return canManage(authentication, id);
    }

    @Override
    public boolean canUpdate(Authentication authentication, SubscriptionChangeRequest request) {
        // The subscription is named by the route, not the body, so the decision cannot be made
        // from the request. canManage is what the routes call.
        return false;
    }

    @Override
    public boolean canDelete(Authentication authentication, UUID id) {
        return canManage(authentication, id);
    }

    /**
     * Not a route. A subscription is created by paying for one through checkout, never by asking
     * this service to make one - Stripe's identifier for it does not exist until then.
     *
     * @param authentication the caller
     * @param request unused
     * @return false, always
     */
    @Override
    public boolean canCreate(Authentication authentication, SubscriptionChangeRequest request) {
        return false;
    }

    /**
     * Not a route. Subscriptions are listed per person, never across the platform: the listing
     * would be every paying customer, which is a report rather than an API.
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
}
