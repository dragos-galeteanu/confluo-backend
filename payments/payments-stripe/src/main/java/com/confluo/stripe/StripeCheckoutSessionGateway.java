package com.confluo.stripe;

import com.stripe.StripeClient;
import com.stripe.exception.StripeException;
import com.stripe.model.checkout.Session;
import com.stripe.net.RequestOptions;
import com.stripe.param.checkout.SessionCreateParams;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.Optional;

/**
 * {@link CheckoutSessionGateway} against Stripe.
 *
 * <p>Nothing but the call, the idempotency key and the mapping. Any decision that appears here is
 * in the wrong place.</p>
 */
@RequiredArgsConstructor
@Slf4j
public class StripeCheckoutSessionGateway implements CheckoutSessionGateway {

    private final StripeClient stripe;

    @Override
    public CheckoutSession create(SessionCreateParams params, String idempotencyKey) {
        try {
            Session session = stripe.checkout().sessions().create(params,
                    RequestOptions.builder().setIdempotencyKey(idempotencyKey).build());
            return new CheckoutSession(session.getId(), session.getUrl(), session.getStatus());
        } catch (StripeException e) {
            throw new StripeOperationException(
                    "Could not create a Stripe Checkout session for %s".formatted(idempotencyKey), e);
        }
    }

    @Override
    public Optional<CheckoutSession> findOpen(String sessionId) {
        Session session;
        try {
            session = stripe.checkout().sessions().retrieve(sessionId);
        } catch (StripeException e) {
            // Not fatal to the caller: it wanted to reuse an existing session and can make a new
            // one instead. Worth a warning, because a session this service recorded and Stripe
            // cannot find is a disagreement worth knowing about.
            log.warn("Could not retrieve Stripe Checkout session {}: {}", sessionId, e.getMessage());
            return Optional.empty();
        }

        CheckoutSession mapped = new CheckoutSession(session.getId(), session.getUrl(), session.getStatus());
        return mapped.isOpen() ? Optional.of(mapped) : Optional.empty();
    }
}
