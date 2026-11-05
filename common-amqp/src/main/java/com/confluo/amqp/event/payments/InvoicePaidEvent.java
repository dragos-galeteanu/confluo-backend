package com.confluo.amqp.event.payments;

import com.confluo.amqp.event.DomainEvent;

import java.time.Instant;
import java.util.UUID;

/**
 * An invoice was paid. What a receipt is sent for.
 *
 * <p>{@code hostedInvoiceUrl} and {@code invoicePdf} are Stripe's own, and are carried rather than
 * rendered here: Stripe already produces a correct invoice document, in the right language, with
 * the right tax breakdown, and keeps it available. A receipt that links to it is better than one
 * that tries to reproduce it.</p>
 *
 * @param invoiceId the invoice, which is also the envelope's aggregate id
 * @param firebaseUid who paid it
 * @param subscriptionId the subscription it is a cycle of, or null for a one-off invoice
 * @param number Stripe's human-readable number, which is what somebody quotes when they ask
 * @param amountPaid what was collected, in minor units
 * @param taxAmount what of it was tax
 * @param currency uppercase ISO 4217
 * @param periodStart the start of the period billed, or null
 * @param periodEnd the end of it, or null
 * @param hostedInvoiceUrl Stripe's hosted copy
 * @param invoicePdf Stripe's generated PDF
 */
@DomainEvent(aggregate = "invoice", event = "paid")
public record InvoicePaidEvent(
        UUID invoiceId,
        String firebaseUid,
        UUID subscriptionId,
        String number,
        long amountPaid,
        long taxAmount,
        String currency,
        Instant periodStart,
        Instant periodEnd,
        String hostedInvoiceUrl,
        String invoicePdf) {
}
