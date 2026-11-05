package com.confluo.payments.connect.dto;

import java.time.Instant;

/**
 * Where to send a seller to finish onboarding.
 *
 * <p><strong>Single-use, and it expires in minutes.</strong> That is Stripe's design rather than a
 * detail: a link that could be reused or shared would be a way into somebody else's account
 * settings. So it must not be emailed, stored, or put in a page that might be reloaded later -
 * redirect to it immediately, and ask for a new one if that fails.</p>
 *
 * <p>{@code expiresAt} is returned so a caller can tell a stale link from a broken one rather than
 * guessing.</p>
 *
 * @param url Stripe's hosted onboarding page
 * @param expiresAt when the link stops working
 * @param accountUpdate whether this link opens the update form rather than first-time onboarding,
 *                      which is what a seller who has already submitted their details gets
 */
public record OnboardingLinkResponse(String url, Instant expiresAt, boolean accountUpdate) {
}
