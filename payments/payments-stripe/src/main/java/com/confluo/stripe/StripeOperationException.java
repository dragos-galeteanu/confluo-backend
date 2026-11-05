package com.confluo.stripe;

/**
 * Stripe could not do what it was asked.
 *
 * <p>Unchecked, so it travels out of the lambdas and {@code Optional} chains the services are
 * written in, and reaches {@code GlobalExceptionHandler} as a 500 - which is the honest status: the
 * caller's request was fine and this platform could not complete it.</p>
 *
 * <p>It exists so that a Stripe failure is distinguishable in a log and a stack trace from a
 * failure of this service's own. The cause is always the {@code StripeException}, which carries
 * Stripe's request id - the one thing worth having when asking them about it.</p>
 */
public class StripeOperationException extends RuntimeException {

    /**
     * @param message what was being attempted
     * @param cause what Stripe said
     */
    public StripeOperationException(String message, Throwable cause) {
        super(message, cause);
    }
}
