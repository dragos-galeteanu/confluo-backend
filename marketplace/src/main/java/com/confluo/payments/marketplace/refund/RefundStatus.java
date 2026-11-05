package com.confluo.payments.marketplace.refund;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Where a refund has got to, mirroring Stripe's own four states.
 *
 * <p>Named exactly as Stripe names them, uppercased, so that ingesting one is
 * {@code valueOf(status.toUpperCase(Locale.ROOT))} and not a translation table that has to be kept
 * current. That is also why {@link #CANCELED} carries Stripe's single {@code l} rather than the
 * spelling used elsewhere in this codebase: the alternative is a mapping nobody remembers to
 * extend.</p>
 *
 * <p>A refund is not instant and {@link #FAILED} is real: the money can bounce back days later if
 * the payer's card or bank account has since closed, and the refund then has to be reissued by
 * another route.</p>
 */
public enum RefundStatus {

    /** submitted to the payer's bank, which has not yet confirmed it */
    PENDING,

    /** the money is on its way back and Stripe considers it done */
    SUCCEEDED,

    /** the bank refused it, usually because the destination no longer exists */
    FAILED,

    /** withdrawn before it was processed */
    CANCELED;

    private static final Logger log = LoggerFactory.getLogger(RefundStatus.class);

    /**
     * Stripe's refund status as this service's own.
     *
     * <p>Lives here rather than in either caller because there are two, and they must agree: a
     * refund this service issues is written with the status Stripe reported from the create call,
     * and the webhook then moves it. Two copies of this mapping would be two chances for a refund
     * to be born in one state and immediately corrected to another.</p>
     *
     * <p>{@code requires_action} maps to {@link #PENDING} because that is what it means here: the
     * money has not gone back and the refund has not failed. An unrecognised status maps there too,
     * and says so - Stripe may add one, and the safe reading of a status this service does not know
     * is "not finished", since treating it as succeeded would tell somebody their money is on its
     * way when it may not be.</p>
     *
     * @param status Stripe's own value, or null
     * @return the mapped status, never null
     */
    public static RefundStatus of(String status) {
        if (status == null) {
            return PENDING;
        }
        return switch (status) {
            case "succeeded" -> SUCCEEDED;
            case "failed" -> FAILED;
            case "canceled" -> CANCELED;
            case "pending", "requires_action" -> PENDING;
            default -> {
                log.warn("Unrecognised Stripe refund status {}; treating it as {}", status, PENDING);
                yield PENDING;
            }
        };
    }
}
