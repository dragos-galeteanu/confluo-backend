package com.confluo.payments.marketplace.refund.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * Ask for money to be sent back.
 *
 * <p>The payment rather than the order, because a refund is against a charge: an order can have
 * more than one payment behind it and only this service knows which one holds the money. A caller
 * holding an order reference and no payment id does not want this route at all - it wants to cancel
 * the order, and the cancellation reaches here as an event.</p>
 *
 * @param paymentId the payment to refund, as this service's own id
 * @param amount how much to send back, in the currency's minor units, or <strong>null</strong> for
 *               everything not already refunded. Null is not the same as sending the full amount:
 *               an omitted amount is passed to Stripe as an omission, which keeps a retried request
 *               identical to the first even after the remainder has moved
 * @param reason Stripe's reason code, or null for {@link RefundReason#REQUESTED_BY_CUSTOMER}. Not
 *               the human reason; see {@code notes}
 * @param notes why, in the words of whoever asked. Kept on the refund and never sent to Stripe,
 *              which is what makes it safe to write what actually happened in it
 * @param idempotencyKey the caller's own key, or null to derive one from the payment and the
 *                       amount. Deriving is right almost always - it is what makes a double tap or
 *                       a lost response harmless. Supplying one is for the single case the derived
 *                       key cannot express: a second refund of the <em>same</em> amount against the
 *                       same payment, issued deliberately, which the derived key would otherwise
 *                       resolve to the first
 */
public record RefundCreateRequest(

        @NotNull
        UUID paymentId,

        @Positive
        Long amount,

        RefundReason reason,

        @Size(max = 1000)
        String notes,

        @Size(max = 200)
        String idempotencyKey
) {
}
