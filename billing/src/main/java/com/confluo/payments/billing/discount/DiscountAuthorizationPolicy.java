package com.confluo.payments.billing.discount;

import com.confluo.common.security.BaseAuthorizationPolicy;
import com.confluo.payments.billing.discount.dto.DiscountCreateRequest;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Who may create, read and withdraw money off.
 *
 * <p>Writing is Admin only, which should need no argument: a discount is the platform's revenue
 * given away, and the ability to create one with no redemption limit is the ability to give away an
 * unbounded amount of it.</p>
 *
 * <p><strong>Reading is Admin or Support, and not every authenticated caller</strong> - which is
 * where this differs from the plan catalog. A plan is a public price list. The discounts are a
 * marketing position: which codes exist, what each is worth, how many are left and who has used
 * them. A customer who can list them can use the best one, and a competitor who can list them
 * knows the platform's margins.</p>
 *
 * <p>Checking a single code is the exception, and the reason this policy exists separately from
 * {@code catalogPolicy}: a payer has to be able to find out whether the code they were given
 * works. {@link #canCheck} therefore allows any authenticated caller to ask about <em>one</em> code
 * they already hold, which tells them nothing they did not already have - while listing stays
 * closed. It is checked for the uid in the request as well, so that asking cannot be used to
 * discover somebody else's redemption history.</p>
 */
@Component("discountPolicy")
public class DiscountAuthorizationPolicy
        implements BaseAuthorizationPolicy<DiscountCreateRequest, Void, UUID> {

    @Override
    public boolean canCreate(Authentication authentication, DiscountCreateRequest request) {
        return isAdmin(authentication);
    }

    @Override
    public boolean canGetById(Authentication authentication, UUID id) {
        return isAdminOrSupport(authentication);
    }

    @Override
    public boolean canGetAll(Authentication authentication, com.confluo.common.filter.PaginationFilter filter) {
        return isAdminOrSupport(authentication);
    }

    /**
     * Not a route. A discount cannot be updated at all - a Stripe coupon's shape is immutable -
     * so this denies rather than describing an operation that does not exist.
     *
     * @param authentication the caller
     * @param request unused
     * @return false, always
     */
    @Override
    public boolean canUpdate(Authentication authentication, Void request) {
        return false;
    }

    @Override
    public boolean canDelete(Authentication authentication, UUID id) {
        return isAdmin(authentication);
    }

    /**
     * Whether the caller may withdraw a discount.
     *
     * <p>Narrowed from the interface's Admin-or-Support default: withdrawing a code stops a
     * campaign, which is a commercial decision rather than a support action.</p>
     *
     * @param authentication the caller
     * @return true if they are an administrator
     */
    @Override
    public boolean canDisable(Authentication authentication) {
        return isAdmin(authentication);
    }

    /**
     * Whether the caller may ask about one code on somebody's behalf.
     *
     * <p>Themselves, or Admin or Support for anybody - the latter because "why won't this code
     * work for me" is a support question, and answering it requires asking as that person.</p>
     *
     * @param authentication the caller
     * @param firebaseUid whose eligibility is being asked about
     * @return true if they may ask
     */
    public boolean canCheck(Authentication authentication, String firebaseUid) {
        if (!isAuthenticated(authentication) || firebaseUid == null) {
            return false;
        }
        if (isAdminOrSupport(authentication)) {
            return true;
        }
        return authentication.getPrincipal() instanceof Jwt jwt
                && firebaseUid.equals(jwt.getSubject());
    }
}
