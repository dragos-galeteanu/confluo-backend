package com.confluo.payments.identity;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers one identity handler per Stripe event type.
 *
 * <p>{@link IdentityVerificationHandler} is one class because every verification event says the
 * same thing, and this is where the list of types lives - the same arrangement as
 * {@code InvoiceHandlerConfiguration}.</p>
 *
 * <p>{@code identity.verification_session.created} is not among them: this service already wrote
 * the row when it asked Stripe for the session, so the event would only tell it what it just did.
 * {@code redacted} is not either - it says Stripe has erased the collected data, which is its
 * retention working as intended and changes nothing about the outcome.</p>
 */
@Configuration(proxyBeanMethods = false)
public class IdentityHandlerConfiguration {

    /**
     * Stripe is satisfied. The one event anything should act on.
     *
     * @param verifications the mirrored attempts
     * @return the handler
     */
    @Bean
    public IdentityVerificationHandler identityVerifiedHandler(
            IdentityVerificationRepository verifications) {
        return new IdentityVerificationHandler(
                "identity.verification_session.verified", verifications);
    }

    /**
     * Stripe needs something else from the person.
     *
     * <p>The document would not scan, the selfie did not match, the photograph was of a document
     * Stripe does not accept. {@code lastErrorReason} is Stripe's wording and the only text fit to
     * put in front of them.</p>
     *
     * @param verifications the mirrored attempts
     * @return the handler
     */
    @Bean
    public IdentityVerificationHandler identityRequiresInputHandler(
            IdentityVerificationRepository verifications) {
        return new IdentityVerificationHandler(
                "identity.verification_session.requires_input", verifications);
    }

    /**
     * The documents are in and Stripe is checking them.
     *
     * <p>Worth recording so that the person is told to wait rather than asked to submit again,
     * which is what starting a second session would do.</p>
     *
     * @param verifications the mirrored attempts
     * @return the handler
     */
    @Bean
    public IdentityVerificationHandler identityProcessingHandler(
            IdentityVerificationRepository verifications) {
        return new IdentityVerificationHandler(
                "identity.verification_session.processing", verifications);
    }

    /**
     * The check was abandoned.
     *
     * <p>Terminal at Stripe, so the next attempt is a new session rather than a resumption of this
     * one.</p>
     *
     * @param verifications the mirrored attempts
     * @return the handler
     */
    @Bean
    public IdentityVerificationHandler identityCanceledHandler(
            IdentityVerificationRepository verifications) {
        return new IdentityVerificationHandler(
                "identity.verification_session.canceled", verifications);
    }
}
