package com.confluo.webhook;

import com.stripe.exception.SignatureVerificationException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Where Stripe tells this service what has happened.
 *
 * <p>Two endpoints, because Stripe issues one signing secret per registered endpoint and the
 * platform's own events and its connected accounts' events are two registrations. One endpoint with
 * one secret cannot verify both.</p>
 *
 * <p><strong>The signature is the authentication.</strong> These are the only routes in the service
 * that no Firebase token can reach, and they are permitted in {@code SecurityConfig} only because
 * {@link StripeWebhookService} verifies every request against the right secret before anything is
 * written. A permitted route with nothing verifying signatures would be an open endpoint accepting
 * unauthenticated writes about money.</p>
 *
 * <h2>Three details that are easy to get wrong</h2>
 *
 * <ul>
 *   <li><strong>The body is taken as a {@code String}.</strong> The signature is an HMAC over the
 *       exact bytes Stripe sent, so a parsed DTO - which Spring would re-render before anything
 *       could hash it - fails verification every time. This is the single most common way a Stripe
 *       integration is broken in Spring, and it fails for everything rather than for edge
 *       cases.</li>
 *   <li><strong>Everything verified is a 200</strong>, duplicate included. Stripe retries anything
 *       that is not a 2xx for days, so answering a redelivery with a 4xx would have it keep
 *       trying to deliver an event already recorded.</li>
 *   <li><strong>Nothing is applied in the request.</strong> The row is committed and the response
 *       returned; handlers run afterwards. A handler slow enough for Stripe to time out would
 *       otherwise cause a redelivery of work already in progress.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/payments/webhooks/stripe")
@RequiredArgsConstructor
@Tag(name = "Stripe webhooks")
@Slf4j
public class StripeWebhookController {

    private final StripeWebhookService service;

    /**
     * The platform account's own events: payments, subscriptions, invoices, disputes.
     *
     * @param payload the raw request body
     * @param signatureHeader the {@code Stripe-Signature} header, which is what authenticates this
     * @return the outcome, with a 200 for anything that verified
     * @throws SignatureVerificationException if it did not, which the handler below turns into a 400
     */
    @PostMapping
    @Operation(
            summary = "Receive a Stripe webhook for the platform account",
            description = "Authenticated by the Stripe-Signature header, not by a bearer token. "
                    + "Called by Stripe only.")
    @SecurityRequirements
    public ResponseEntity<WebhookAck> platform(
            @RequestBody String payload,
            @RequestHeader("Stripe-Signature") String signatureHeader) throws SignatureVerificationException {

        StripeEventIngestion outcome = service.ingest(StripeWebhookSource.PLATFORM, payload, signatureHeader);
        return ResponseEntity.ok(new WebhookAck(outcome));
    }

    /**
     * Events about connected accounts: onboarding state, capabilities, transfers, payouts.
     *
     * <p>A separate route with a separate secret. Its events carry the {@code acct_} id of the
     * account they concern, which the platform endpoint's never do.</p>
     *
     * @param payload the raw request body
     * @param signatureHeader the {@code Stripe-Signature} header
     * @return the outcome, with a 200 for anything that verified
     * @throws SignatureVerificationException if it did not
     */
    @PostMapping("/connect")
    @Operation(
            summary = "Receive a Stripe webhook for a connected account",
            description = "Verified with the Connect endpoint's own signing secret, which is a "
                    + "different value from the platform endpoint's.")
    @SecurityRequirements
    public ResponseEntity<WebhookAck> connect(
            @RequestBody String payload,
            @RequestHeader("Stripe-Signature") String signatureHeader) throws SignatureVerificationException {

        StripeEventIngestion outcome = service.ingest(StripeWebhookSource.CONNECT, payload, signatureHeader);
        return ResponseEntity.ok(new WebhookAck(outcome));
    }

    /**
     * Answers a request that did not come from Stripe, or did not come from Stripe intact.
     *
     * <p>A 400 with no body, and nothing about why. The caller is either Stripe - in which case
     * this is a misconfigured secret and the log says so - or somebody probing, who learns only
     * that it was refused. {@code GlobalExceptionHandler} in {@code common-core} deliberately does
     * not handle this: Stripe's exception types have no business reaching the shared module.</p>
     *
     * <p>The same 400 covers a tampered body and a timestamp outside Stripe's tolerance, which is
     * the replay protection - a captured request cannot be sent again five minutes later.</p>
     *
     * @param e what the verifier refused
     * @return a 400
     */
    @ExceptionHandler(SignatureVerificationException.class)
    public ResponseEntity<Void> signatureRejected(SignatureVerificationException e) {
        log.warn("Rejected a Stripe webhook whose signature did not verify: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
    }
}
