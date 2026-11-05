package com.confluo.payments.marketplace;

import java.util.OptionalInt;

/**
 * Whether a seller has bought themselves a smaller cut.
 *
 * <p>Declared here and implemented in billing, which is the whole point of it. What the platform
 * keeps from a sale is this package's business; what a subscription tier buys is billing's, and a
 * marketplace that queried subscriptions to price a sale was a marketplace that could not be built
 * without billing. The dependency now runs the other way, which is the direction it reads in
 * anyway: billing knows about sales, a sale does not know about plans.</p>
 *
 * <p>There is deliberately no default implementation. A missing one is a startup failure rather
 * than every seller quietly paying the standard rate, because the difference between those two
 * outcomes is money and the second one is silent. A deployment that genuinely has no subscriptions
 * supplies an implementation that returns empty, and says so out loud by doing it.</p>
 */
public interface PlatformFeeOverrideSource {

    /**
     * The rate this seller is entitled to, in basis points.
     *
     * <p>Empty means no entitlement rather than a rate of zero, which is why this is not simply an
     * {@code int}: zero is a legitimate override - a tier that pays no commission at all - and the
     * two have to be distinguishable or the most generous tier would read as the cheapest
     * mistake.</p>
     *
     * <p>Called on the path of a checkout, with somebody waiting, so an implementation that cannot
     * answer from one query does not belong behind this.</p>
     *
     * @param sellerFirebaseUid whose sale it is
     * @return their rate, or empty if nothing they hold carries one
     */
    OptionalInt basisPointsFor(String sellerFirebaseUid);
}
