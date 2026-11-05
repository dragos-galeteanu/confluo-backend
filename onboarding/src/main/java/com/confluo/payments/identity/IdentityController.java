package com.confluo.payments.identity;

import com.confluo.payments.identity.dto.IdentityVerificationLinkResponse;
import com.confluo.payments.identity.dto.IdentityVerificationResponse;
import com.confluo.payments.identity.dto.IdentityVerificationStartRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Proving who somebody is.
 *
 * <p>Two routes, and no listing - see {@code IdentityAuthorizationPolicy} for why that absence is
 * deliberate.</p>
 *
 * <p>Separate from {@code ConnectController} because the two answer different questions. Identity
 * checks a person's documents; Connect decides whether an account may be paid. A seller can pass
 * one and fail the other, and a single endpoint reporting "verified" would hide which.</p>
 */
@RestController
@RequestMapping("/api/payments/identity/verifications")
@RequiredArgsConstructor
@Tag(name = "Identity verification")
public class IdentityController {

    private final IdentityVerificationService service;

    /**
     * Starts an identity check, or picks up one already under way.
     *
     * <p>Safe to call again: somebody mid-check is handed back the same session rather than asked
     * to submit their documents twice, and somebody Stripe is still checking gets a status with no
     * link, because there is nothing for them to do.</p>
     *
     * <p>The returned URL is as good as a password for this check - redirect to it, and do not
     * email, log or store it. A 200 means a check is under way; it never means somebody is
     * verified, which only the webhook decides.</p>
     *
     * @param request whose identity
     * @return where to send them, or the status that means nowhere
     */
    @PostMapping
    @Operation(
            summary = "Start or resume an identity check",
            description = "Returns a hosted Stripe URL to redirect to, or no URL when Stripe is "
                    + "still checking documents already submitted. Never means verified.")
    @PreAuthorize("@identityPolicy.canCreate(authentication, #request)")
    public ResponseEntity<IdentityVerificationLinkResponse> start(
            @Valid @RequestBody IdentityVerificationStartRequest request) {
        return ResponseEntity.ok(service.start(request.firebaseUid()));
    }

    /**
     * Somebody's latest identity check.
     *
     * <p>The latest rather than a history: a verification can be superseded, so "are they
     * verified" is answered by the most recent attempt. {@code lastErrorReason} is Stripe's own
     * wording of what went wrong and the only text fit to show the person.</p>
     *
     * @param firebaseUid whose identity
     * @return their latest attempt
     */
    @GetMapping("/{firebaseUid}")
    @Operation(summary = "Get somebody's latest identity check and why it failed, if it did")
    @PreAuthorize("@identityPolicy.canAccessVerificationOf(authentication, #firebaseUid)")
    public ResponseEntity<IdentityVerificationResponse> getLatest(@PathVariable String firebaseUid) {
        return ResponseEntity.ok(service.getLatest(firebaseUid));
    }
}
