package com.confluo.payments.marketplace.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * Ask for a Checkout session so somebody can pay for something.
 *
 * <p>Called by the buyer's own client, or by support on their behalf - not by the service that
 * owns orders, which never enters this path: a checkout answers with a URL somebody has to be
 * redirected to, and interservice communication here is AMQP. That service learns what happened
 * from {@code payment.succeeded} instead.</p>
 *
 * <p>{@code orderReferenceType} and {@code orderReferenceId} are the only coupling between the two
 * services, and this one never interprets either: they come back unchanged on the payment, on the
 * events it publishes, and in Stripe's own metadata.</p>
 *
 * <p>{@code amount} is in the currency's minor units, as Stripe states amounts, and is the price
 * <strong>before</strong> tax. Stripe Tax adds what is owed on top during checkout, which is why
 * the amount finally charged can be higher than this and why it is read back off the completed
 * session rather than assumed.</p>
 *
 * @param orderReferenceType what kind of thing is being paid for, as its owning service names it
 * @param orderReferenceId its id in that service
 * @param buyerFirebaseUid who is paying
 * @param sellerFirebaseUid whose connected account the money is transferred on to
 * @param amount the price before tax, in minor units
 * @param currency uppercase ISO 4217
 * @param description what the payer sees on Stripe's page as the thing they are buying
 */
public record OrderCheckoutRequest(

        @NotBlank
        @Size(max = 64)
        String orderReferenceType,

        @NotBlank
        @Size(max = 128)
        String orderReferenceId,

        @NotBlank
        @Size(max = 128)
        String buyerFirebaseUid,

        @NotBlank
        @Size(max = 128)
        String sellerFirebaseUid,

        @Positive
        long amount,

        @NotBlank
        @Pattern(regexp = "^[A-Z]{3}$", message = "must be an uppercase ISO 4217 code")
        String currency,

        @NotBlank
        @Size(max = 255)
        String description
) {
}
