package com.confluo.payments.billing.invoice;

import com.confluo.amqp.publish.EventPublisher;
import com.confluo.payments.billing.subscription.SubscriptionRepository;
import com.confluo.payments.customer.StripeCustomerRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers one invoice handler per Stripe event type.
 *
 * <p>{@link InvoiceMirroringHandler} is one class because every invoice event says the same thing -
 * here is the invoice as it now stands - and the status comes off the invoice rather than from
 * which event carried it. So the handlers are five beans of one type rather than five classes, and
 * this is where the list of types lives.</p>
 *
 * <p>Adding a type is a line here. Removing one stops this service mirroring it, which is the
 * right lever: a type that is not wanted should stop being applied, and the sweep then records such
 * events as {@code IGNORED} rather than leaving them waiting.</p>
 *
 * <p>{@code invoice.upcoming} is not in the list, and must not be: it carries a preview with no id.
 * See the handler's javadoc.</p>
 */
@Configuration(proxyBeanMethods = false)
public class InvoiceHandlerConfiguration {

    /**
     * An invoice has been issued and is now owed.
     *
     * <p>The first event for most invoices, and where the row usually comes from. Not
     * {@code created}: a draft invoice is not owed by anybody and Stripe may still change it.</p>
     *
     * @param invoices the mirrored invoices
     * @param subscriptions the mirrored subscriptions
     * @param customers the identity mapping
     * @param events where a receipt or a dunning notice is announced
     * @return the handler
     */
    @Bean
    public InvoiceMirroringHandler invoiceFinalizedHandler(
            InvoiceRepository invoices,
            SubscriptionRepository subscriptions,
            StripeCustomerRepository customers,
            EventPublisher events) {
        return new InvoiceMirroringHandler("invoice.finalized", invoices, subscriptions, customers, events);
    }

    /**
     * An invoice was paid. What a receipt is sent for.
     *
     * @param invoices the mirrored invoices
     * @param subscriptions the mirrored subscriptions
     * @param customers the identity mapping
     * @param events where a receipt or a dunning notice is announced
     * @return the handler
     */
    @Bean
    public InvoiceMirroringHandler invoicePaidHandler(
            InvoiceRepository invoices,
            SubscriptionRepository subscriptions,
            StripeCustomerRepository customers,
            EventPublisher events) {
        return new InvoiceMirroringHandler("invoice.paid", invoices, subscriptions, customers, events);
    }

    /**
     * Collection failed and Stripe will try again.
     *
     * <p>The dunning window. The invoice's own {@code attempt_count} and
     * {@code next_payment_attempt} are what a reminder is worded from, and they arrive on this
     * event - the retry schedule is Stripe Billing's, which is why this service records it rather
     * than deciding it.</p>
     *
     * @param invoices the mirrored invoices
     * @param subscriptions the mirrored subscriptions
     * @param customers the identity mapping
     * @param events where a receipt or a dunning notice is announced
     * @return the handler
     */
    @Bean
    public InvoiceMirroringHandler invoicePaymentFailedHandler(
            InvoiceRepository invoices,
            SubscriptionRepository subscriptions,
            StripeCustomerRepository customers,
            EventPublisher events) {
        return new InvoiceMirroringHandler(
                "invoice.payment_failed", invoices, subscriptions, customers, events);
    }

    /**
     * An invoice was cancelled as though it had never been issued.
     *
     * <p>Not the same as a refund, and worth keeping distinct: a void invoice was never owed, while
     * a refunded one was owed, paid and returned.</p>
     *
     * @param invoices the mirrored invoices
     * @param subscriptions the mirrored subscriptions
     * @param customers the identity mapping
     * @param events where a receipt or a dunning notice is announced
     * @return the handler
     */
    @Bean
    public InvoiceMirroringHandler invoiceVoidedHandler(
            InvoiceRepository invoices,
            SubscriptionRepository subscriptions,
            StripeCustomerRepository customers,
            EventPublisher events) {
        return new InvoiceMirroringHandler("invoice.voided", invoices, subscriptions, customers, events);
    }

    /**
     * An invoice was written off: owed, not collected, no longer chased.
     *
     * <p>The accounting end of a failed dunning sequence. The row stays, which is the point - what
     * was written off is a question asked long afterwards.</p>
     *
     * @param invoices the mirrored invoices
     * @param subscriptions the mirrored subscriptions
     * @param customers the identity mapping
     * @param events where a receipt or a dunning notice is announced
     * @return the handler
     */
    @Bean
    public InvoiceMirroringHandler invoiceMarkedUncollectibleHandler(
            InvoiceRepository invoices,
            SubscriptionRepository subscriptions,
            StripeCustomerRepository customers,
            EventPublisher events) {
        return new InvoiceMirroringHandler(
                "invoice.marked_uncollectible", invoices, subscriptions, customers, events);
    }
}
