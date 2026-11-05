package com.confluo.payments.pricing;

import com.confluo.common.filter.PaginationFilter;
import com.confluo.common.security.BaseAuthorizationPolicy;
import com.confluo.payments.pricing.dto.PlanCreateRequest;
import com.confluo.payments.pricing.dto.PlanUpdateRequest;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Who may read and change what this platform sells.
 *
 * <p>Writing is Admin only, and for the same reason the notifications service restricts its
 * templates: a plan is a price and a set of entitlements, so the ability to change one is the
 * ability to decide what everybody pays and what they get for it. A fee override is the ability to
 * change what the platform earns on every sale a seller makes.</p>
 *
 * <p>Reading is any authenticated caller. A pricing page is the obvious consumer, and a tier's name
 * and price are what a customer is being asked to agree to - there is nothing to protect in them.
 * What is <strong>not</strong> readable without a token is the same thing by accident: the response
 * carries {@code stripeProductId}, and the whole service is closed by default.</p>
 *
 * <p>One policy covers both plans and their prices, because the rule is identical and does not
 * depend on the request at all - it turns on the caller's role alone. The price methods therefore
 * take only the authentication and have no counterpart on the interface: a price is a sub-resource
 * reached through its plan's route, so {@code canGetById} and the rest have nothing to say about
 * it.</p>
 */
@Component("catalogPolicy")
public class CatalogAuthorizationPolicy
        implements BaseAuthorizationPolicy<PlanCreateRequest, PlanUpdateRequest, UUID> {

    @Override
    public boolean canCreate(Authentication authentication, PlanCreateRequest request) {
        return isAdmin(authentication);
    }

    @Override
    public boolean canGetById(Authentication authentication, UUID id) {
        return isAuthenticated(authentication);
    }

    @Override
    public boolean canGetAll(Authentication authentication, PaginationFilter filter) {
        return isAuthenticated(authentication);
    }

    @Override
    public boolean canUpdate(Authentication authentication, PlanUpdateRequest request) {
        return isAdmin(authentication);
    }

    @Override
    public boolean canDelete(Authentication authentication, UUID id) {
        return isAdmin(authentication);
    }

    /**
     * Whether the caller may disable a tier.
     *
     * <p>Overridden to narrow it. The interface's default is Admin or Support, which is right for
     * most resources and not for this one: withdrawing a tier stops everybody buying it, which is
     * a commercial decision rather than a support action.</p>
     *
     * @param authentication the caller
     * @return true if they are an administrator
     */
    @Override
    public boolean canDisable(Authentication authentication) {
        return isAdmin(authentication);
    }

    /**
     * Whether the caller may re-run a tier's push to Stripe.
     *
     * <p>Admin, like every other write. It changes nothing a reader can see when it succeeds, but
     * it is the operation that makes a tier sellable, so it belongs with the rest.</p>
     *
     * @param authentication the caller
     * @return true if they are an administrator
     */
    public boolean canReconcile(Authentication authentication) {
        return isAdmin(authentication);
    }

    /**
     * Whether the caller may add or withdraw a price.
     * @param authentication the caller
     * @return true if they are an administrator
     */
    public boolean canWritePrices(Authentication authentication) {
        return isAdmin(authentication);
    }

    /**
     * Whether the caller may see what a tier costs.
     * @param authentication the caller
     * @return true if they are authenticated at all
     */
    public boolean canReadPrices(Authentication authentication) {
        return isAuthenticated(authentication);
    }
}
