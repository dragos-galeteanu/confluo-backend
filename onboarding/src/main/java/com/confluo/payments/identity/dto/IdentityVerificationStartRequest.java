package com.confluo.payments.identity.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Ask Stripe to check who somebody is.
 *
 * <p>A uid and nothing else. The document, the selfie and whatever Stripe compares them against are
 * collected on Stripe's own pages and never pass through this platform - which is the whole reason
 * to use Identity rather than asking for a photograph of a passport over HTTP.</p>
 *
 * @param firebaseUid whose identity is being checked
 */
public record IdentityVerificationStartRequest(

        @NotBlank
        @Size(max = 128)
        String firebaseUid
) {
}
