package com.confluo.payments.billing.subscription;

import com.confluo.payments.pricing.PlanAudience;
import com.confluo.payments.marketplace.PlatformFeeOverrideSource;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.OptionalInt;

/**
 * What a seller's subscription buys them off their commission.
 *
 * <p>Billing's answer to the marketplace's question, and the one place where a tier turns into
 * money off a sale. The lowest override any of their entitled provider plans carries, because a
 * seller may hold more than one and the generous one is the one they paid for.</p>
 *
 * <p>Only <strong>provider</strong> plans count. A seller who also subscribes as a customer has not
 * bought a cheaper commission with it, and reading both audiences would hand them one.</p>
 */
@Component
@RequiredArgsConstructor
public class SubscriptionPlatformFeeOverrides implements PlatformFeeOverrideSource {

    /** The states in which a subscription grants what its plan promises. */
    private static final List<SubscriptionStatus> ENTITLED =
            List.of(SubscriptionStatus.TRIALING, SubscriptionStatus.ACTIVE);

    private final SubscriptionRepository subscriptions;

    /**
     * {@inheritDoc}
     *
     * <p>One query; see {@link SubscriptionRepository#findLowestPlatformFeeOverride} for why it is
     * written out rather than walked in Java.</p>
     */
    @Override
    public OptionalInt basisPointsFor(String sellerFirebaseUid) {
        return subscriptions
                .findLowestPlatformFeeOverride(sellerFirebaseUid, ENTITLED, PlanAudience.PROVIDER)
                .map(OptionalInt::of)
                .orElseGet(OptionalInt::empty);
    }
}
