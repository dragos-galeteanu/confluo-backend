package com.confluo.payments.billing.invoice;

import com.confluo.amqp.event.payments.InvoicePaidEvent;
import com.confluo.amqp.event.payments.InvoicePaymentFailedEvent;
import com.confluo.amqp.publish.EventPublisher;
import com.confluo.payments.billing.subscription.Subscription;
import com.confluo.payments.billing.subscription.SubscriptionRepository;
import com.confluo.payments.customer.StripeCustomerRepository;
import com.confluo.webhook.StripeEvent;
import com.confluo.webhook.StripeEventHandler;
import com.stripe.model.Event;
import com.stripe.model.StripeObject;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;


/**
 * Mirrors one invoice, whatever happened to it.
 *
 * <p><strong>One class for five event types, unlike the payment and subscription handlers.</strong>
 * That is not a shortcut: those genuinely differ - a succeeded payment clears an earlier decline, a
 * cancellation refuses to touch a paid one - whereas every invoice event says the same thing, which
 * is "here is the invoice, as it now stands". The status comes off the invoice itself rather than
 * from which event carried it, so five classes would be five copies of one method with different
 * strings in them. The event type is a constructor argument and
 * {@code InvoiceHandlerConfiguration} registers one bean per type.</p>
 *
 * <p><strong>{@code invoice.upcoming} is deliberately not among them.</strong> That event carries a
 * <em>preview</em> of an invoice that does not exist yet and has no id, so there is nothing to
 * mirror and nothing to key a row by. It is useful - it is how "your subscription renews on
 * Tuesday for 29 EUR" gets sent - but that is a notification rather than a record, and it belongs
 * with the events this service publishes.</p>
 *
 * <h2>Two fields Stripe moved</h2>
 *
 * <p>The <strong>subscription</strong> is no longer {@code invoice.subscription}: it is
 * {@code parent.subscription_details.subscription}, reached through a {@code Parent} that also
 * covers invoices generated from a quote. And the <strong>tax</strong> is no longer
 * {@code invoice.tax}: it is the sum of {@code total_taxes}, which can hold several entries when
 * more than one jurisdiction applies. Both of the old fields are simply absent, so code written
 * against them compiles and finds nothing.</p>
 */
@Slf4j
public class InvoiceMirroringHandler implements StripeEventHandler {

    /** The one event type announced on every occurrence rather than on a transition. */
    private static final String PAYMENT_FAILED_EVENT = "invoice.payment_failed";

    private final String eventType;
    private final InvoiceRepository invoices;
    private final SubscriptionRepository subscriptions;
    private final StripeCustomerRepository customers;
    private final EventPublisher events;

    /**
     * @param eventType which Stripe event this bean handles
     * @param invoices the mirrored invoices
     * @param subscriptions the mirrored subscriptions, to attach the invoice to one
     * @param customers the identity mapping, for an invoice with no subscription
     * @param events where a receipt or a dunning notice is announced
     */
    public InvoiceMirroringHandler(
            String eventType,
            InvoiceRepository invoices,
            SubscriptionRepository subscriptions,
            StripeCustomerRepository customers,
            EventPublisher events) {
        this.eventType = eventType;
        this.invoices = invoices;
        this.subscriptions = subscriptions;
        this.customers = customers;
        this.events = events;
    }

    @Override
    public String eventType() {
        return eventType;
    }

    @Override
    public void handle(StripeEvent ledgerRow, Event event) {
        com.stripe.model.Invoice stripeInvoice = invoiceOf(event);

        Optional<Invoice> existing = invoices.findByStripeInvoiceId(stripeInvoice.getId());
        if (existing.isPresent() && isStale(ledgerRow, existing.get().getLastStripeEventAt())) {
            log.info("Ignoring Stripe event {} for invoice {}: it occurred at {}, and {} has "
                            + "already been applied",
                    ledgerRow.getStripeEventId(), stripeInvoice.getId(),
                    ledgerRow.getEventCreatedAt(), existing.get().getLastStripeEventAt());
            return;
        }

        Invoice invoice = existing.orElseGet(Invoice::new);
        // Read before the setters below. `existing.get()` and `invoice` are the same object, so
        // capturing this afterwards would read the new status as the old one and no transition
        // would ever be announced.
        InvoiceStatus before = existing.map(Invoice::getStatus).orElse(null);
        if (existing.isEmpty()) {
            invoice.setStripeInvoiceId(stripeInvoice.getId());

            Subscription subscription = subscriptionOf(stripeInvoice);
            invoice.setSubscription(subscription);

            String firebaseUid = subscription != null
                    ? subscription.getFirebaseUid()
                    : uidFromCustomer(stripeInvoice);
            if (firebaseUid == null) {
                throw new IllegalStateException(
                        "Stripe invoice %s names nobody this service knows: it has no subscription "
                                .formatted(stripeInvoice.getId())
                                + "here and its customer is not mapped");
            }
            invoice.setFirebaseUid(firebaseUid);
        } else if (invoice.getSubscription() == null) {
            // An invoice finalised before its subscription's own event arrived has no subscription
            // attached yet. Worth filling in on a later event rather than leaving a billing
            // history that cannot be grouped by what it was for.
            invoice.setSubscription(subscriptionOf(stripeInvoice));
        }

        invoice.setNumber(stripeInvoice.getNumber());
        invoice.setStatus(statusOf(stripeInvoice.getStatus()));
        invoice.setAmountDue(orZero(stripeInvoice.getAmountDue()));
        invoice.setAmountPaid(orZero(stripeInvoice.getAmountPaid()));
        invoice.setTaxAmount(taxOf(stripeInvoice));
        if (stripeInvoice.getCurrency() != null) {
            // Stripe speaks lowercase; the column is constrained to upper, and this is the boundary.
            invoice.setCurrency(stripeInvoice.getCurrency().toUpperCase(Locale.ROOT));
        }
        invoice.setPeriodStart(instant(stripeInvoice.getPeriodStart()));
        invoice.setPeriodEnd(instant(stripeInvoice.getPeriodEnd()));
        invoice.setHostedInvoiceUrl(stripeInvoice.getHostedInvoiceUrl());
        invoice.setInvoicePdf(stripeInvoice.getInvoicePdf());
        invoice.setAttemptCount((int) orZero(stripeInvoice.getAttemptCount()));
        invoice.setNextPaymentAttempt(instant(stripeInvoice.getNextPaymentAttempt()));
        invoice.setLastStripeEventAt(ledgerRow.getEventCreatedAt());

        Invoice saved = invoices.save(invoice);
        announce(saved, before);

        if (saved.getStatus() == InvoiceStatus.OPEN && saved.getAttemptCount() > 0) {
            // The dunning window: Stripe is still retrying, and these two fields are what a
            // reminder is worded from. "We will try again on Tuesday" is only true coming from the
            // side doing the trying.
            log.warn("Invoice unpaid and being retried [id={}, uid={}, attempts={}, nextAttempt={}, "
                            + "due={} {}]",
                    saved.getId(), saved.getFirebaseUid(), saved.getAttemptCount(),
                    saved.getNextPaymentAttempt(), saved.getAmountDue(), saved.getCurrency());
        } else {
            log.info("Invoice mirrored [id={}, uid={}, status={}, paid={} of {} {}, event={}]",
                    saved.getId(), saved.getFirebaseUid(), saved.getStatus(),
                    saved.getAmountPaid(), saved.getAmountDue(), saved.getCurrency(), eventType);
        }
    }

    /**
     * Announces a receipt or a dunning notice.
     *
     * <p>The two are published on different rules, and the difference is the point.</p>
     *
     * <p>{@code invoice.paid} goes out on the <strong>transition</strong> into paid, like every
     * other event this service publishes: Stripe will mention a paid invoice again, and a second
     * receipt for one payment is worse than none.</p>
     *
     * <p>{@code invoice.payment_failed} goes out on <strong>every attempt</strong>, which is the
     * one deliberate exception to that rule. Each failure is a new fact rather than a repetition of
     * the same state - the attempt count has moved and Stripe will next try at a different time -
     * and a dunning sequence that escalates needs to hear about each one. Publishing only on the
     * first would send one reminder and then go quiet while the subscription lapsed.</p>
     *
     * @param invoice the invoice as it now stands
     * @param before the status it had before, or null if this is the first time it has been seen
     */
    private void announce(Invoice invoice, InvoiceStatus before) {
        UUID subscriptionId = invoice.getSubscription() == null
                ? null
                : invoice.getSubscription().getId();

        if (invoice.getStatus() == InvoiceStatus.PAID && before != InvoiceStatus.PAID) {
            UUID eventId = events.publish(invoice.getId(), new InvoicePaidEvent(
                    invoice.getId(),
                    invoice.getFirebaseUid(),
                    subscriptionId,
                    invoice.getNumber(),
                    invoice.getAmountPaid(),
                    invoice.getTaxAmount(),
                    invoice.getCurrency(),
                    invoice.getPeriodStart(),
                    invoice.getPeriodEnd(),
                    invoice.getHostedInvoiceUrl(),
                    invoice.getInvoicePdf()));
            log.info("Announced invoice.paid [invoice={}, event={}]", invoice.getId(), eventId);
            return;
        }

        if (PAYMENT_FAILED_EVENT.equals(eventType)) {
            UUID eventId = events.publish(invoice.getId(), new InvoicePaymentFailedEvent(
                    invoice.getId(),
                    invoice.getFirebaseUid(),
                    subscriptionId,
                    invoice.getNumber(),
                    invoice.getAmountDue(),
                    invoice.getCurrency(),
                    invoice.getAttemptCount(),
                    invoice.getNextPaymentAttempt(),
                    invoice.getHostedInvoiceUrl()));
            log.info("Announced invoice.payment_failed [invoice={}, attempt={}, event={}]",
                    invoice.getId(), invoice.getAttemptCount(), eventId);
        }
    }

    /**
     * The subscription an invoice belongs to, or null.
     *
     * <p>Null for an invoice that is not a subscription's: one raised from a quote, or billed out
     * of band. Also null for a subscription this service does not know, which is worth a warning
     * rather than a failure - the invoice is still a real charge against a real person and belongs
     * in their billing history either way.</p>
     *
     * @param stripeInvoice what Stripe sent
     * @return the subscription, or null
     */
    private Subscription subscriptionOf(com.stripe.model.Invoice stripeInvoice) {
        if (stripeInvoice.getParent() == null
                || stripeInvoice.getParent().getSubscriptionDetails() == null) {
            return null;
        }
        String stripeSubscriptionId =
                stripeInvoice.getParent().getSubscriptionDetails().getSubscription();
        if (stripeSubscriptionId == null) {
            return null;
        }
        Optional<Subscription> subscription =
                subscriptions.findByStripeSubscriptionId(stripeSubscriptionId);
        if (subscription.isEmpty()) {
            log.warn("Stripe invoice {} belongs to subscription {}, which this service does not "
                            + "have yet", stripeInvoice.getId(), stripeSubscriptionId);
        }
        return subscription.orElse(null);
    }

    private String uidFromCustomer(com.stripe.model.Invoice stripeInvoice) {
        String customerId = stripeInvoice.getCustomer();
        if (customerId == null) {
            return null;
        }
        return customers.findByStripeCustomerId(customerId)
                .map(com.confluo.payments.customer.StripeCustomer::getFirebaseUid)
                .orElse(null);
    }

    /**
     * What of the invoice was tax.
     *
     * <p>A sum, because {@code total_taxes} holds one entry per rate that applied - a sale taxed by
     * two jurisdictions has two, and taking the first would understate it. The old scalar
     * {@code invoice.tax} no longer exists.</p>
     *
     * @param stripeInvoice what Stripe sent
     * @return the tax, in minor units
     */
    private long taxOf(com.stripe.model.Invoice stripeInvoice) {
        if (stripeInvoice.getTotalTaxes() == null) {
            return 0L;
        }
        return stripeInvoice.getTotalTaxes().stream()
                .map(com.stripe.model.Invoice.TotalTax::getAmount)
                .filter(amount -> amount != null)
                .mapToLong(Long::longValue)
                .sum();
    }

    /**
     * Stripe's invoice status as this service's own.
     *
     * <p>Throws on anything unrecognised rather than defaulting, for the reason the subscription
     * handler gives: the plausible defaults here are "paid" and "not paid", and both are wrong in
     * expensive ways. Failing leaves the row as it was and puts it in front of a person.</p>
     *
     * @param status Stripe's own value
     * @return the mapped status
     */
    private InvoiceStatus statusOf(String status) {
        if (status == null) {
            throw new IllegalStateException("A Stripe invoice with no status cannot be applied");
        }
        try {
            return InvoiceStatus.valueOf(status.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(
                    "Stripe reported invoice status '%s', which this service does not know; it is "
                            .formatted(status) + "left unapplied rather than guessed at");
        }
    }

    private boolean isStale(StripeEvent ledgerRow, Instant lastApplied) {
        return lastApplied != null
                && ledgerRow.getEventCreatedAt() != null
                && !ledgerRow.getEventCreatedAt().isAfter(lastApplied);
    }

    private com.stripe.model.Invoice invoiceOf(Event event) {
        Optional<StripeObject> deserialized = event.getDataObjectDeserializer().getObject();
        StripeObject object = deserialized.orElseGet(() -> {
            log.warn("Stripe event {} is at API version {} but this service pins {}; "
                            + "deserialising it anyway",
                    event.getId(), event.getApiVersion(), com.stripe.Stripe.API_VERSION);
            try {
                return event.getDataObjectDeserializer().deserializeUnsafe();
            } catch (Exception e) {
                throw new IllegalStateException(
                        "Could not read the data object of Stripe event %s".formatted(event.getId()), e);
            }
        });
        if (!(object instanceof com.stripe.model.Invoice invoice)) {
            throw new IllegalStateException(
                    "Stripe event %s (%s) carries a %s, not an Invoice".formatted(
                            event.getId(), event.getType(), object.getClass().getSimpleName()));
        }
        return invoice;
    }

    private static long orZero(Long value) {
        return value == null ? 0L : value;
    }

    private static Instant instant(Long epochSeconds) {
        return epochSeconds == null ? null : Instant.ofEpochSecond(epochSeconds);
    }
}
