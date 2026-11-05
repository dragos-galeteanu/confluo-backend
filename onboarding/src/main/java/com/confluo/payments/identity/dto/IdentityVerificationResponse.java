package com.confluo.payments.identity.dto;

import com.confluo.payments.identity.IdentityVerificationStatus;

import java.time.Instant;
import java.util.UUID;

/**
 * Where somebody's identity check has got to.
 *
 * <p>The latest attempt, not a history: "are they verified" is answered by the most recent session,
 * because a verification can be superseded. {@code lastErrorCode} and {@code lastErrorReason} are
 * Stripe's, and the reason is the only text fit to show the person - "the document was not
 * readable" is actionable, while a code is not.</p>
 *
 * <p>Nothing here is this platform's judgement, and nothing in it comes from a document: Stripe
 * decides, and this records the decision.</p>
 *
 * @param id this service's id for the attempt
 * @param firebaseUid whose identity it is
 * @param stripeVerificationSessionId Stripe's own {@code vs_} identifier
 * @param status where the check has got to
 * @param verifiedAt when Stripe became satisfied, or null
 * @param lastErrorCode Stripe's code for why the last attempt was not enough, or null
 * @param lastErrorReason Stripe's wording of it, which is what the person is shown
 * @param createdAt when the attempt was started
 */
public record IdentityVerificationResponse(
        UUID id,
        String firebaseUid,
        String stripeVerificationSessionId,
        IdentityVerificationStatus status,
        Instant verifiedAt,
        String lastErrorCode,
        String lastErrorReason,
        Instant createdAt
) {
}
