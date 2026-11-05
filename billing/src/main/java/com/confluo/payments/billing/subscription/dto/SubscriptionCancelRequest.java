package com.confluo.payments.billing.subscription.dto;

/**
 * End a subscription.
 *
 * <p>{@code atPeriodEnd} is the whole request, and the two answers are very different. Letting it
 * run to the end of the period gives the subscriber what they have already paid for; ending it now
 * forfeits the remainder, because Stripe raises no credit note for the unused days.</p>
 *
 * <p>It is therefore deliberately not defaulted anywhere: a caller has to say which, and the one
 * that takes something away is the one they have to ask for.</p>
 *
 * @param atPeriodEnd true to stop renewing at the end of the paid period, false to end it now
 */
public record SubscriptionCancelRequest(boolean atPeriodEnd) {
}
