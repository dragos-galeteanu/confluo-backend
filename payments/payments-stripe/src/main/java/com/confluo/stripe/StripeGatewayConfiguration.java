package com.confluo.stripe;

import com.stripe.StripeClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The seams, one bean each.
 *
 * <p>Declared rather than component-scanned, which is the same choice {@code common-amqp} makes
 * and for the same reason: a service that puts this module on its classpath has not asked for
 * anything yet. Nothing here exists until {@link EnableStripe} imports it, so adding the dependency
 * - to read a gateway's record type, say - cannot quietly give a service six beans and a Stripe
 * client built from credentials it never configured.</p>
 *
 * <p>Every bean is {@code @ConditionalOnMissingBean}, so a service can replace one gateway without
 * giving up the rest. That is what a seam is for.</p>
 */
@Configuration(proxyBeanMethods = false)
public class StripeGatewayConfiguration {

    /**
     * @param stripe the client
     * @return checkout sessions, for a sale and for a subscription
     */
    @Bean
    @ConditionalOnMissingBean(CheckoutSessionGateway.class)
    public CheckoutSessionGateway checkoutSessionGateway(StripeClient stripe) {
        return new StripeCheckoutSessionGateway(stripe);
    }

    /**
     * @param stripe the client
     * @return connected accounts and the links that onboard them
     */
    @Bean
    @ConditionalOnMissingBean(ConnectAccountGateway.class)
    public ConnectAccountGateway connectAccountGateway(StripeClient stripe) {
        return new StripeConnectAccountGateway(stripe);
    }

    /**
     * @param stripe the client
     * @return identity verification sessions
     */
    @Bean
    @ConditionalOnMissingBean(IdentityVerificationGateway.class)
    public IdentityVerificationGateway identityVerificationGateway(StripeClient stripe) {
        return new StripeIdentityVerificationGateway(stripe);
    }

    /**
     * @param stripe the client
     * @return refunds
     */
    @Bean
    @ConditionalOnMissingBean(RefundGateway.class)
    public RefundGateway refundGateway(StripeClient stripe) {
        return new StripeRefundGateway(stripe);
    }

    /**
     * @param stripe the client
     * @return subscriptions, as Stripe holds them
     */
    @Bean
    @ConditionalOnMissingBean(SubscriptionGateway.class)
    public SubscriptionGateway subscriptionGateway(StripeClient stripe) {
        return new StripeSubscriptionGateway(stripe);
    }

    /**
     * @param stripe the client
     * @return transfer reversals, which is how a seller's share is taken back
     */
    @Bean
    @ConditionalOnMissingBean(TransferReversalGateway.class)
    public TransferReversalGateway transferReversalGateway(StripeClient stripe) {
        return new StripeTransferReversalGateway(stripe);
    }
}
