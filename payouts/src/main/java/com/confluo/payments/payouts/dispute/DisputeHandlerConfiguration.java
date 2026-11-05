package com.confluo.payments.payouts.dispute;

import com.confluo.amqp.publish.EventPublisher;
import com.confluo.payments.marketplace.PaymentRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers one dispute handler per Stripe event type.
 *
 * <p>{@code funds_withdrawn} and {@code funds_reinstated} are deliberately absent - see
 * {@link DisputeMirroringHandler}.</p>
 */
@Configuration(proxyBeanMethods = false)
public class DisputeHandlerConfiguration {

    /**
     * A payer has charged back. The urgent one: it carries a deadline.
     *
     * @param disputes the mirrored disputes
     * @param payments the mirrored payments
     * @param events where a dispute is announced
     * @param clawback what takes the seller's share back when the dispute is lost
     * @return the handler
     */
    @Bean
    public DisputeMirroringHandler disputeCreatedHandler(
            DisputeRepository disputes,
            PaymentRepository payments,
            EventPublisher events,
            DisputeClawback clawback) {
        return new DisputeMirroringHandler("charge.dispute.created", disputes, payments, events, clawback);
    }

    /**
     * Something about a dispute changed - evidence submitted, a deadline moved.
     *
     * @param disputes the mirrored disputes
     * @param payments the mirrored payments
     * @param events where a dispute is announced
     * @param clawback what takes the seller's share back when the dispute is lost
     * @return the handler
     */
    @Bean
    public DisputeMirroringHandler disputeUpdatedHandler(
            DisputeRepository disputes,
            PaymentRepository payments,
            EventPublisher events,
            DisputeClawback clawback) {
        return new DisputeMirroringHandler("charge.dispute.updated", disputes, payments, events, clawback);
    }

    /**
     * Stripe has settled it, one way or the other.
     *
     * @param disputes the mirrored disputes
     * @param payments the mirrored payments
     * @param events where a dispute is announced
     * @param clawback what takes the seller's share back when the dispute is lost
     * @return the handler
     */
    @Bean
    public DisputeMirroringHandler disputeClosedHandler(
            DisputeRepository disputes,
            PaymentRepository payments,
            EventPublisher events,
            DisputeClawback clawback) {
        return new DisputeMirroringHandler("charge.dispute.closed", disputes, payments, events, clawback);
    }
}
