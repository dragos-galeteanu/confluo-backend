package com.confluo.payments.marketplace;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.OptionalInt;

/**
 * What the platform keeps from a sale.
 *
 * <p>This is where the two halves of the business meet, and the meeting is a one-line interface:
 * the default cut is a setting here, and whether a seller has bought themselves a smaller one is
 * {@link PlatformFeeOverrideSource}, which billing implements. A seller with no entitlement pays
 * the default like everybody else.</p>
 *
 * <p>The arithmetic stays on this side. Which rate applies is a question about what somebody
 * subscribed to; what the platform keeps from <em>this</em> sale is a question about the sale, and
 * the rounding and the clamp below are part of the answer.</p>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class PlatformFeeCalculator {

    /** Basis points in the whole: 10000 bps is 100%. */
    private static final long BASIS_POINTS_SCALE = 10_000L;

    private final PlatformFeeOverrideSource overrides;
    private final CheckoutProperties checkout;

    /**
     * The platform's cut of one sale, in the currency's minor units.
     *
     * <p>Rounded <strong>down</strong>. A fraction of a minor unit has to go to one of the two
     * parties, and giving it to the seller is the one that cannot produce a fee larger than the
     * sale - which Stripe would refuse, failing the checkout rather than the arithmetic.</p>
     *
     * @param sellerFirebaseUid whose sale it is
     * @param amount the sale, in minor units
     * @return the fee, which is never more than the amount
     */
    public long feeFor(String sellerFirebaseUid, long amount) {
        int basisPoints = basisPointsFor(sellerFirebaseUid);
        long fee = amount * basisPoints / BASIS_POINTS_SCALE;

        if (fee > amount) {
            // Unreachable while basisPoints is capped at 10000, which both the property and the
            // plan's CHECK constraint see to. Clamped rather than trusted, because the failure
            // would otherwise be Stripe refusing the session with nothing to point at.
            log.error("A fee of {} on a sale of {} for seller {} is more than the sale; clamping",
                    fee, amount, sellerFirebaseUid);
            return amount;
        }
        return fee;
    }

    /**
     * The rate a seller is charged, in basis points.
     *
     * <p>Whatever {@link PlatformFeeOverrideSource} says this seller is entitled to, or the
     * platform default if it says nothing. Empty is not zero: a tier that charges no commission at
     * all is a legitimate override, and conflating the two would give the standard rate to the
     * sellers who had paid most to avoid it.</p>
     *
     * @param sellerFirebaseUid whose rate it is
     * @return the rate
     */
    public int basisPointsFor(String sellerFirebaseUid) {
        OptionalInt entitled = overrides.basisPointsFor(sellerFirebaseUid);
        return entitled.orElseGet(() -> checkout.platformFeeBasisPoints());
    }
}
