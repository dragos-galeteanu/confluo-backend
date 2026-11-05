package com.confluo.payments.connect;

import com.confluo.payments.connect.dto.ConnectAccountCreateRequest;
import com.confluo.payments.connect.dto.ConnectAccountResponse;
import com.confluo.payments.connect.dto.OnboardingLinkResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;


/**
 * How a seller becomes payable.
 *
 * <p>Not a {@link com.confluo.common.controller.BaseController}: a connected account is not a
 * resource this platform owns. It is Stripe's, reached by the seller's uid rather than by an id of
 * this service's own, and almost everything about it is changed on Stripe's hosted pages rather
 * than through here. So there is no update route and no delete route, and the two writes that do
 * exist - create the account, mint a link - are actions rather than edits.</p>
 */
@RestController
@RequestMapping("/api/payments/connect/accounts")
@RequiredArgsConstructor
@Tag(name = "Connect onboarding")
public class ConnectController {

    private final ConnectOnboardingService service;

    /**
     * Creates a seller's connected account.
     *
     * <p>Safe to call twice: a seller who already has one gets it back rather than a second. That
     * matters more here than for most idempotency - a duplicate account means a second identity
     * verification, a second bank account, and payouts arriving somewhere the seller is not
     * watching.</p>
     *
     * <p>A 201 does <strong>not</strong> mean they can be paid. It means the account exists with
     * everything disabled, which is where every account starts; what they still owe Stripe is in
     * the response's requirements, and the next step is an onboarding link.</p>
     *
     * @param request who, and where they are
     * @return their account, with nothing enabled yet
     */
    @PostMapping
    @Operation(
            summary = "Create a seller's connected account",
            description = "Idempotent on the seller. A 201 means the account exists with charges "
                    + "and payouts disabled - not that the seller is verified.")
    @PreAuthorize("@connectPolicy.canCreate(authentication, #request)")
    public ResponseEntity<ConnectAccountResponse> create(
            @Valid @RequestBody ConnectAccountCreateRequest request) {
        return new ResponseEntity<>(service.createAccount(request), HttpStatus.CREATED);
    }

    /**
     * A fresh link to Stripe's onboarding.
     *
     * <p>Needed every time, because an account link is single-use and expires in minutes. Redirect
     * to it immediately; do not store it, email it, or put it in a page that may be reloaded - it
     * is a way into that seller's account settings.</p>
     *
     * <p>Which form the seller gets depends on where they have got to: first-time onboarding if
     * they have not submitted their details, and the update form if they have, which is what they
     * need to answer a requirement Stripe has since raised.</p>
     *
     * @param firebaseUid whose account
     * @return the link and when it expires
     */
    @PostMapping("/{firebaseUid}/onboarding-link")
    @Operation(
            summary = "Mint a single-use Stripe onboarding link",
            description = "Expires in minutes and cannot be reused. Redirect immediately rather "
                    + "than storing or sending it.")
    @PreAuthorize("@connectPolicy.canAccessAccountOf(authentication, #firebaseUid)")
    public ResponseEntity<OnboardingLinkResponse> onboardingLink(@PathVariable String firebaseUid) {
        return ResponseEntity.ok(service.createOnboardingLink(firebaseUid));
    }

    /**
     * A seller's account, and what Stripe is still waiting on.
     *
     * <p>The requirements are the useful part: they turn "your account is restricted" into "Stripe
     * still needs your ID document", which is the difference between a seller who can act and one
     * who contacts support.</p>
     *
     * @param firebaseUid whose account
     * @return the account
     */
    @GetMapping("/{firebaseUid}")
    @Operation(summary = "Get a seller's connected account and outstanding requirements")
    @PreAuthorize("@connectPolicy.canAccessAccountOf(authentication, #firebaseUid)")
    public ResponseEntity<ConnectAccountResponse> getByFirebaseUid(@PathVariable String firebaseUid) {
        return ResponseEntity.ok(service.getByFirebaseUid(firebaseUid));
    }

    /**
     * Re-reads a seller's account from Stripe.
     *
     * <p>The repair for a row whose {@code account.updated} was missed or failed to apply. Not the
     * ordinary path - the webhook is, and polling every account would be a great many calls to
     * learn nothing.</p>
     *
     * @param firebaseUid whose account
     * @return the account as Stripe currently has it
     */
    @PostMapping("/{firebaseUid}/refresh")
    @Operation(
            summary = "Re-read a connected account from Stripe",
            description = "For a row whose account.updated webhook was missed. The webhook is the "
                    + "ordinary path.")
    @PreAuthorize("@connectPolicy.canAccessAccountOf(authentication, #firebaseUid)")
    public ResponseEntity<ConnectAccountResponse> refresh(@PathVariable String firebaseUid) {
        return ResponseEntity.ok(service.refreshFromStripe(firebaseUid));
    }

    /**
     * The sellers Stripe is still waiting on.
     *
     * <p>Submitted their details and still cannot be paid out - which is the list an onboarding
     * reminder is sent from, and the one worth watching: a seller stuck here is somebody who
     * thinks they have finished.</p>
     *
     * @return their accounts
     */
    @GetMapping("/awaiting-requirements")
    @Operation(summary = "List sellers who have submitted details but cannot yet be paid out")
    @PreAuthorize("@connectPolicy.canListAwaitingRequirements(authentication)")
    public ResponseEntity<List<ConnectAccountResponse>> awaitingRequirements() {
        return ResponseEntity.ok(service.getAwaitingRequirements());
    }
}
