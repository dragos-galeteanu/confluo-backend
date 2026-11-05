package com.confluo.payments.marketplace.refund.dto;

import com.confluo.payments.marketplace.refund.RefundInitiator;
import com.confluo.payments.marketplace.refund.RefundStatus;

import java.time.Instant;
import java.util.UUID;

/**
 * A refund as this service has it.
 *
 * <p>{@code status} is the thing to read and the thing most likely to be misread. A refund that has
 * just been created is {@link RefundStatus#PENDING}, which means Stripe has accepted it and the
 * payer's bank has not confirmed it - so a 200 from the refund route says the money is on its way,
 * not that it has arrived, and {@link RefundStatus#FAILED} days later is a real outcome for a card
 * that has since closed.</p>
 *
 * <p>Carries nothing about the payer. Whose money it was is on the payment, and repeating it here
 * would put a uid in every support response that needed one for nothing.</p>
 *
 * @param id the refund's id in this service
 * @param paymentId what it came off
 * @param stripeRefundId Stripe's own {@code re_} identifier, which is what to quote to them
 * @param amount how much is going back, in minor units - Stripe's figure, which for a refund of
 *               "everything left" is the first time the caller learns what that was
 * @param currency uppercase ISO 4217
 * @param status where it has got to
 * @param initiatedBy who decided it should happen, which is the question asked of a refund that
 *                    turns out to have been wrong
 * @param reason Stripe's reason code, or null
 * @param notes why, in the words of whoever asked
 * @param createdAt when this service recorded it
 */
public record RefundResponse(
        UUID id,
        UUID paymentId,
        String stripeRefundId,
        long amount,
        String currency,
        RefundStatus status,
        RefundInitiator initiatedBy,
        String reason,
        String notes,
        Instant createdAt) {
}
