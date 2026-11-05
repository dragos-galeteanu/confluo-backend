package com.confluo.payments.identity.dto;

import com.confluo.payments.identity.IdentityVerificationStatus;

/**
 * Where to send somebody to prove who they are.
 *
 * <p><strong>{@code url} is as good as a password for this check.</strong> Anybody holding it can
 * submit documents against the session, so it must be redirected to and never emailed, logged or
 * stored. Stripe's client secret is deliberately not in this response at all: it would be a second
 * way to do the same thing, and this platform has no use for it.</p>
 *
 * <p>{@code url} is null when there is nothing for the person to do - which happens when Stripe is
 * still checking what they already submitted. {@code status} says which case it is, and a caller
 * should read it rather than assuming a link is always there.</p>
 *
 * @param url Stripe's hosted page, or null when the person has nothing left to do
 * @param status where the check has got to
 * @param resumed whether this continues an attempt already under way rather than starting one
 */
public record IdentityVerificationLinkResponse(
        String url, IdentityVerificationStatus status, boolean resumed) {
}
