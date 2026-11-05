package com.confluo.payments.marketplace.refund;

import com.confluo.common.filter.PaginationFilter;
import com.confluo.common.security.BaseAuthorizationPolicy;
import com.confluo.payments.marketplace.Payment;
import com.confluo.payments.marketplace.PaymentRepository;
import com.confluo.payments.marketplace.refund.dto.RefundCreateRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

import java.util.UUID;


/**
 * Who may send money back.
 *
 * <p>Support and administrators, and nobody else. A payer cannot refund their own payment through
 * this service, which is a deliberate narrowing of what {@link RefundInitiator} allows for.</p>
 *
 * <h2>Why the payer is not here</h2>
 *
 * <p>Because this service cannot answer the question that decides it: <strong>whether the thing
 * bought has been delivered</strong>. It holds an order reference it never resolves, so a
 * self-service refund here would be unconditional - the payer gets their money back whether or not
 * the seller has already shipped. That is right for a deposit and wrong for a delivered good, and
 * payments has no way to tell which it is looking at.</p>
 *
 * <p>So a payer who wants their money back cancels the <strong>order</strong>. The service that
 * owns orders knows what has actually happened and can apply whatever rule the business has;
 * its cancellation arrives here as {@code order.cancelled} and is handled by
 * {@code OnOrderCancelled}, which refunds under {@link RefundInitiator#ORDER_EVENT}. The rule lives
 * in the service that can evaluate it, and the money moves in the service that holds it.</p>
 *
 * <p>{@link RefundInitiator#USER_REQUEST} therefore has nothing that produces it. It is kept - in
 * the enum and in the schema's own check constraint - because the decision is about where the rule
 * belongs rather than about whether a payer may ever refund anything, and removing the value would
 * make reinstating that a migration.</p>
 *
 * <h2>Reading is not refunding</h2>
 *
 * <p>The payer may still <em>see</em> what has been refunded against their own payment, which is
 * how somebody finds out their money is on its way. Only issuing one is closed to them.</p>
 *
 * <p>That read is why this policy touches the database, which none of the others here do: a route
 * addressed by a payment id says nothing about who paid it, so the only way to know whether the
 * caller is the payer is to look.</p>
 */
@Component("refundPolicy")
@RequiredArgsConstructor
@Slf4j
public class RefundAuthorizationPolicy
        implements BaseAuthorizationPolicy<RefundCreateRequest, Void, UUID> {

    private final PaymentRepository payments;

    /**
     * Whether a caller may refund this payment.
     *
     * <p>No ownership check, because being the payer is not a reason to be allowed: see the class
     * javadoc. The payment is not even looked up - this is a question about the caller alone, and
     * a lookup here would only tell us something we are not going to act on.</p>
     *
     * @param authentication the caller
     * @param request what they want refunded
     * @return true for support and administrators only
     */
    @Override
    public boolean canCreate(Authentication authentication, RefundCreateRequest request) {
        if (!isAuthenticated(authentication) || request == null || request.paymentId() == null) {
            return false;
        }
        return isAdminOrSupport(authentication);
    }

    /**
     * Whether a caller may see what has been refunded against a payment.
     *
     * <p>Addressed by the <strong>payment's</strong> id, which is what the route carries: a refund
     * is not asked for on its own here, because the question anybody actually has is "what has come
     * back off this payment?".</p>
     *
     * @param authentication the caller
     * @param paymentId the payment whose refunds are being read
     * @return true for support and administrators, or for the payer themselves
     */
    @Override
    public boolean canGetById(Authentication authentication, UUID paymentId) {
        if (!isAuthenticated(authentication) || paymentId == null) {
            return false;
        }
        if (isAdminOrSupport(authentication)) {
            return true;
        }
        return isPayer(authentication, paymentId);
    }

    /**
     * What to record as the provenance of a refund this caller asked for.
     *
     * <p>Here rather than in the controller because it is an authorization question - what the
     * caller is, not what they asked for - and {@link RefundInitiator} is the one column that
     * answers "why was this refunded?" later.</p>
     *
     * <p>One answer today, and it still <strong>checks</strong> rather than returning a constant.
     * That is the point of the method: if {@link #canCreate(Authentication, RefundCreateRequest)}
     * is ever widened to let somebody else refund, this refuses instead of filing their refund as
     * support's. A provenance column that quietly says the wrong thing is worse than a route that
     * stops working, because nobody goes looking for it.</p>
     *
     * @param authentication the caller, which {@code canCreate} has already allowed
     * @return {@link RefundInitiator#SUPPORT}
     * @throws AccessDeniedException if the caller is not support, which means the route was opened
     *                               without deciding what to record for whoever it was opened to
     */
    public RefundInitiator initiatorFor(Authentication authentication) {
        if (!isAdminOrSupport(authentication)) {
            throw new AccessDeniedException(
                    "Only support may refund through this service; a refund for anybody else has to "
                            + "come from the order being cancelled, so there is no provenance to "
                            + "record for this caller");
        }
        return RefundInitiator.SUPPORT;
    }

    /**
     * Whether the caller is the person who made this payment.
     *
     * <p>A payment that does not exist is not the caller's, so this denies rather than letting the
     * request through to be refused as not found. The difference matters: answering "no such
     * payment" to anybody who asks turns this route into a way to find out which payment ids
     * exist.</p>
     *
     * @param authentication the caller
     * @param paymentId the payment
     * @return true if they match
     */
    private boolean isPayer(Authentication authentication, UUID paymentId) {
        String uid = firebaseUidOf(authentication);
        if (uid == null) {
            return false;
        }
        boolean theirs = payments.findById(paymentId)
                .map(Payment::getFirebaseUid)
                .map(uid::equals)
                .orElse(false);
        if (!theirs) {
            log.debug("Refund denied: {} is not the payer of payment {}", uid, paymentId);
        }
        return theirs;
    }

    /**
     * The caller's Firebase uid.
     *
     * <p>The subject, not {@code SecurityUtils.extractUserId}: that returns a {@link UUID}, and a
     * Firebase uid is not one.</p>
     *
     * @param authentication the caller
     * @return their uid, or null if the principal is not a token
     */
    private String firebaseUidOf(Authentication authentication) {
        return authentication.getPrincipal() instanceof Jwt jwt ? jwt.getSubject() : null;
    }

    @Override
    public boolean canGetAll(Authentication authentication, PaginationFilter filter) {
        // Deliberately closed. A list of every refund on the platform is a list of everything that
        // went wrong with money, and it is answerable one payment at a time.
        return false;
    }

    @Override
    public boolean canUpdate(Authentication authentication, Void request) {
        // A refund cannot be changed once Stripe has it, so there is no route and nothing to allow.
        return false;
    }

    @Override
    public boolean canDelete(Authentication authentication, UUID id) {
        return false;
    }
}
