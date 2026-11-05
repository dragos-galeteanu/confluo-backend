package com.confluo.payments.billing.subscription;

import com.confluo.payments.billing.subscription.dto.SubscriptionCancelRequest;
import com.confluo.payments.billing.subscription.dto.SubscriptionChangeRequest;
import com.confluo.payments.billing.subscription.dto.SubscriptionResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;


/**
 * Changing and ending a subscription.
 *
 * <p>Not a {@link com.confluo.common.controller.BaseController}. There is no create - a
 * subscription comes into existence by being paid for through checkout - and no delete, because
 * ending one leaves the row: what somebody was paying for until March is a question asked long
 * after they have gone.</p>
 *
 * <p>Every write here reports <strong>Stripe's</strong> answer, not the stored row. The webhook is
 * the only thing that writes mirrored state, and it arrives moments later - see
 * {@code SubscriptionManagementService} for why it is done that way round.</p>
 */
@RestController
@RequestMapping("/api/payments/subscriptions")
@RequiredArgsConstructor
@Tag(name = "Subscriptions")
public class SubscriptionController {

    private final SubscriptionManagementService service;

    /**
     * Moves a subscription onto a different tier or billing period.
     *
     * <p>Immediately, with the difference invoiced there and then rather than waiting for the next
     * renewal. An upgrade somebody has paid for starts at once; a downgrade credits them at once
     * too, which appears on that same invoice.</p>
     *
     * <p>The currency cannot change - Stripe will not do it - so the new price is looked up in the
     * one the subscription is already billed in.</p>
     *
     * @param id which subscription
     * @param request what to move it to
     * @return the subscription as Stripe now has it
     */
    @PatchMapping("/{id}")
    @Operation(
            summary = "Change a subscription's plan or billing period",
            description = "Takes effect immediately and invoices the difference now. The currency "
                    + "cannot change, because Stripe will not change it.")
    @PreAuthorize("@subscriptionPolicy.canManage(authentication, #id)")
    public ResponseEntity<SubscriptionResponse> change(
            @PathVariable UUID id,
            @Valid @RequestBody SubscriptionChangeRequest request) {
        return ResponseEntity.ok(service.change(id, request));
    }

    /**
     * Ends a subscription.
     *
     * <p>A POST rather than a DELETE, because nothing is deleted: the row stays with its status and
     * the time it ended. {@code atPeriodEnd} is not defaulted - the caller has to say which, and
     * the one that takes something away is the one they must ask for, since Stripe raises no credit
     * note for the unused days.</p>
     *
     * @param id which subscription
     * @param request whether to stop now or at the end of the paid period
     * @return the subscription as Stripe now has it
     */
    @PostMapping("/{id}/cancel")
    @Operation(
            summary = "End a subscription, now or at the end of the paid period",
            description = "Ending it now forfeits the remainder: Stripe raises no credit note for "
                    + "the unused days.")
    @PreAuthorize("@subscriptionPolicy.canManage(authentication, #id)")
    public ResponseEntity<SubscriptionResponse> cancel(
            @PathVariable UUID id,
            @Valid @RequestBody SubscriptionCancelRequest request) {
        return ResponseEntity.ok(service.cancel(id, request.atPeriodEnd()));
    }

    /**
     * Calls off a cancellation that was scheduled for the period end.
     *
     * <p>Only while it is still running. Once the period has closed there is nothing to resume and
     * a new subscription is the only way back.</p>
     *
     * @param id which subscription
     * @return the subscription as Stripe now has it
     */
    @PostMapping("/{id}/resume")
    @Operation(summary = "Call off a scheduled cancellation")
    @PreAuthorize("@subscriptionPolicy.canManage(authentication, #id)")
    public ResponseEntity<SubscriptionResponse> resume(@PathVariable UUID id) {
        return ResponseEntity.ok(service.resume(id));
    }

    /**
     * One subscription.
     * @param id which subscription
     * @return the subscription
     */
    @GetMapping("/{id}")
    @Operation(summary = "Get a subscription")
    @PreAuthorize("@subscriptionPolicy.canGetById(authentication, #id)")
    public ResponseEntity<SubscriptionResponse> getById(@PathVariable UUID id) {
        return ResponseEntity.ok(service.getById(id));
    }

    /**
     * Everything somebody holds.
     *
     * <p>More than one is normal: both sides of the marketplace subscribe, so a person can be a
     * paying provider and a paying customer at the same time.</p>
     *
     * @param firebaseUid whose subscriptions
     * @return their subscriptions
     */
    @GetMapping("/by-uid/{firebaseUid}")
    @Operation(summary = "List somebody's subscriptions")
    @PreAuthorize("@subscriptionPolicy.canListFor(authentication, #firebaseUid)")
    public ResponseEntity<List<SubscriptionResponse>> getForUser(@PathVariable String firebaseUid) {
        return ResponseEntity.ok(service.getForUser(firebaseUid));
    }
}
