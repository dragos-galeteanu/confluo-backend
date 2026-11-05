package com.confluo.payments.marketplace;

import com.confluo.common.exception.ResourceNotFoundException;
import com.confluo.payments.connect.ConnectedAccount;
import com.confluo.payments.connect.ConnectedAccountRepository;
import com.confluo.payments.connect.ConnectedAccountState;
import com.confluo.payments.customer.StripeCustomer;
import com.confluo.payments.customer.StripeCustomerService;
import com.confluo.payments.marketplace.dto.CheckoutSessionResponse;
import com.confluo.payments.marketplace.dto.OrderCheckoutRequest;
import com.confluo.stripe.CheckoutSessionGateway;
import com.confluo.stripe.StripeOperationException;
import com.stripe.exception.StripeException;
import com.stripe.param.checkout.SessionCreateParams;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Locale;
import java.util.Optional;


/**
 * Sets up a marketplace sale so somebody can pay for it.
 *
 * <h2>Destination charges</h2>
 *
 * <p>One charge, on the platform's account, with the seller's connected account as the transfer
 * destination and the platform's cut as the application fee. So the platform is the merchant of
 * record: it appears on the payer's statement, it owns the dispute, and it is the one Stripe debits
 * if the payer charges back. {@code PayoutTransfer} and {@code Dispute} are both about the
 * consequences of that.</p>
 *
 * <h2>Order of operations, and why</h2>
 *
 * <p>The payment row is written and <strong>committed</strong> before Stripe is called. Nothing
 * else is safe: Stripe can deliver {@code checkout.session.completed} within a second of the payer
 * paying, and a row written afterwards is a payment that can succeed with nothing recording it.</p>
 *
 * <p>Which leaves a gap the other way - the row exists and the session does not - and that gap is
 * closed by two things rather than by trying to be atomic. The row's {@code idempotencyKey} is
 * unique, so a retry finds it and resumes rather than charging twice; and the session carries the
 * row's id as its {@code client_reference_id}, so even a session whose id was never stored back can
 * be traced to its payment by the webhook. That is why this service is deliberately not
 * {@code @Transactional}: a transaction spanning the Stripe call would hold a database connection
 * across a network round trip and still not make the two atomic.</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OrderCheckoutService {

    private final PaymentRepository payments;
    private final ConnectedAccountRepository accounts;
    private final StripeCustomerService customers;
    private final PlatformFeeCalculator fees;
    private final CheckoutSessionGateway gateway;
    private final CheckoutProperties checkout;

    /**
     * Sets up, or picks up, the payment for one order.
     *
     * @param request what is being sold, to whom, by whom
     * @return where to send the payer
     * @throws ResourceNotFoundException if the seller has no connected account here
     * @throws IllegalStateException if the seller cannot be charged for, if the order has already
     *         been paid, or if the same order is asked for again at a different price
     */
    public CheckoutSessionResponse checkout(OrderCheckoutRequest request) {
        String idempotencyKey = idempotencyKeyFor(request);

        Optional<Payment> existing = payments.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            return resume(existing.get(), request, idempotencyKey);
        }

        ConnectedAccount seller = sellerOf(request);
        StripeCustomer buyer = buyerOf(request);
        long fee = fees.feeFor(request.sellerFirebaseUid(), request.amount());

        // Committed here, before Stripe is told anything. See the class javadoc.
        Payment payment = record(request, seller, fee, idempotencyKey);

        CheckoutSessionGateway.CheckoutSession session =
                gateway.create(sessionParams(payment, request, seller, buyer), idempotencyKey);

        payment.setStripeCheckoutSessionId(session.id());
        payment = payments.save(payment);

        log.info("Checkout session created [payment={}, session={}, order={}:{}, amount={} {}, fee={}]",
                payment.getId(), session.id(), request.orderReferenceType(), request.orderReferenceId(),
                payment.getAmount(), payment.getCurrency(), fee);
        return response(payment, session.url());
    }

    /**
     * What to do when this order has been asked about before.
     *
     * <p>Three different situations, and only one of them is an error:</p>
     *
     * <ul>
     *   <li>it has been <strong>paid</strong>, or is settling - refused, because the caller is
     *       about to charge somebody twice for one order and the only safe answer is to say so;</li>
     *   <li>its session is <strong>still open</strong> - the payer is sent back to the same page,
     *       which is what a caller retrying after a lost response wants;</li>
     *   <li>its session has <strong>expired or failed</strong> - a new session is made against the
     *       same payment row, so the order keeps one payment however many times the payer gives up
     *       and comes back.</li>
     * </ul>
     *
     * @param payment the payment the key already belongs to
     * @param request what was asked for this time
     * @param idempotencyKey the key both share
     * @return where to send the payer
     */
    private CheckoutSessionResponse resume(
            Payment payment, OrderCheckoutRequest request, String idempotencyKey) {

        // Paid first, and the order of these two checks matters. Once a session completes, the
        // payment's amount is the total Stripe actually collected - which includes the tax Stripe
        // Tax added and is therefore higher than the pre-tax amount the caller sends. Comparing
        // prices first would answer a request about an order that has been paid with a confusing
        // complaint about the price instead of the fact that somebody has already paid it.
        if (payment.getStatus() == PaymentStatus.SUCCEEDED
                || payment.getStatus() == PaymentStatus.PROCESSING) {
            throw new IllegalStateException(
                    "Order %s:%s has already been paid (payment %s is %s)"
                            .formatted(request.orderReferenceType(), request.orderReferenceId(),
                                    payment.getId(), payment.getStatus()));
        }

        // The same order at a different price is not a retry, it is two different intentions
        // sharing a key, and guessing which one is meant is worse than refusing. Safe to compare
        // here because nothing has been collected: an unpaid payment still holds the amount this
        // service asked for.
        if (payment.getAmount() != request.amount()
                || !payment.getCurrency().equals(request.currency())) {
            throw new IllegalStateException(
                    "Order %s:%s is already set up for %d %s and cannot be asked for again at %d %s"
                            .formatted(request.orderReferenceType(), request.orderReferenceId(),
                                    payment.getAmount(), payment.getCurrency(),
                                    request.amount(), request.currency()));
        }

        if (payment.getStripeCheckoutSessionId() != null) {
            Optional<CheckoutSessionGateway.CheckoutSession> open =
                    gateway.findOpen(payment.getStripeCheckoutSessionId());
            if (open.isPresent()) {
                log.info("Reusing the open Checkout session for payment {}", payment.getId());
                return response(payment, open.get().url());
            }
        }

        ConnectedAccount seller = sellerOf(request);
        StripeCustomer buyer = buyerOf(request);

        // A new session needs a key of its own: Stripe would otherwise hand back the first
        // session, which is the very one that has expired.
        String retryKey = idempotencyKey + ":" + payment.getUpdatedAt().toEpochMilli();
        CheckoutSessionGateway.CheckoutSession session =
                gateway.create(sessionParams(payment, request, seller, buyer), retryKey);

        payment.setStripeCheckoutSessionId(session.id());
        payment.setStatus(PaymentStatus.REQUIRES_PAYMENT);
        payment.setFailureCode(null);
        payment.setFailureMessage(null);
        payment = payments.save(payment);

        log.info("Replaced the expired Checkout session for payment {} with {}",
                payment.getId(), session.id());
        return response(payment, session.url());
    }

    /**
     * The seller's connected account, if they can actually be sold for.
     *
     * <p>Checked here rather than left to Stripe, because Stripe's refusal arrives as a failed
     * session creation with nothing useful to show the seller, while this one can say which
     * requirement is outstanding - the account's own {@code requirementsCurrentlyDue} is the
     * answer, and {@link ConnectedAccountState} is how it is summarised.</p>
     *
     * @param request the request
     * @return the account
     */
    private ConnectedAccount sellerOf(OrderCheckoutRequest request) {
        ConnectedAccount seller = accounts.findByFirebaseUid(request.sellerFirebaseUid())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Seller %s has no connected account".formatted(request.sellerFirebaseUid())));

        if (!seller.isChargesEnabled()) {
            throw new IllegalStateException(
                    "Seller %s cannot be sold for: their Stripe account is %s%s"
                            .formatted(request.sellerFirebaseUid(), seller.getState(),
                                    seller.getRequirementsCurrentlyDue().isEmpty()
                                            ? ""
                                            : " and still owes " + seller.getRequirementsCurrentlyDue()));
        }
        return seller;
    }

    private StripeCustomer buyerOf(OrderCheckoutRequest request) {
        try {
            return customers.resolveOrCreate(request.buyerFirebaseUid());
        } catch (StripeException e) {
            throw new StripeOperationException(
                    "Could not resolve a Stripe customer for %s".formatted(request.buyerFirebaseUid()), e);
        }
    }

    private Payment record(
            OrderCheckoutRequest request, ConnectedAccount seller, long fee, String idempotencyKey) {

        Payment payment = new Payment();
        payment.setFirebaseUid(request.buyerFirebaseUid());
        payment.setConnectedAccountId(seller.getId());
        // Copied, not joined. Everything downstream - the sale event, a dispute, a clawback - needs
        // the seller's uid, and the account that holds it belongs to onboarding.
        payment.setSellerFirebaseUid(seller.getFirebaseUid());
        payment.setSellerStripeAccountId(seller.getStripeAccountId());
        payment.setOrderReferenceType(request.orderReferenceType());
        payment.setOrderReferenceId(request.orderReferenceId());
        payment.setAmount(request.amount());
        payment.setCurrency(request.currency());
        payment.setApplicationFeeAmount(fee);
        payment.setStatus(PaymentStatus.REQUIRES_PAYMENT);
        payment.setIdempotencyKey(idempotencyKey);
        return payments.save(payment);
    }

    /**
     * What the session should be.
     *
     * <p>Every line here is a decision worth knowing about:</p>
     *
     * <ul>
     *   <li>{@code clientReferenceId} is the payment's own id, which is what lets a webhook find
     *       the row even if the session's id was never written back to it;</li>
     *   <li>{@code transferData.destination} and {@code applicationFeeAmount} together are what
     *       make this a destination charge with a platform cut;</li>
     *   <li>{@code automaticTax} with {@code liability = self} says the platform owes the tax,
     *       which follows from being merchant of record - {@code account} would be wrong here and
     *       would put the obligation on the seller;</li>
     *   <li>{@code billingAddressCollection = REQUIRED} and {@code customerUpdate.address = AUTO}
     *       are what Stripe Tax needs: an address to calculate from, and permission to save it
     *       back to the customer so the next purchase does not ask again;</li>
     *   <li>the amount is sent as inline {@code priceData} rather than a Stripe Price, because an
     *       order is a one-off and creating a Price per sale would fill the account with
     *       single-use objects.</li>
     * </ul>
     *
     * @param payment the committed row
     * @param request what is being sold
     * @param seller whose account receives the money
     * @param buyer the Stripe customer paying
     * @return the parameters
     */
    private SessionCreateParams sessionParams(
            Payment payment, OrderCheckoutRequest request, ConnectedAccount seller, StripeCustomer buyer) {

        return SessionCreateParams.builder()
                .setMode(SessionCreateParams.Mode.PAYMENT)
                .setSuccessUrl(checkout.successUrl())
                .setCancelUrl(checkout.cancelUrl())
                .setClientReferenceId(payment.getId().toString())
                .setCustomer(buyer.getStripeCustomerId())
                .setBillingAddressCollection(SessionCreateParams.BillingAddressCollection.REQUIRED)
                .setCustomerUpdate(SessionCreateParams.CustomerUpdate.builder()
                        .setAddress(SessionCreateParams.CustomerUpdate.Address.AUTO)
                        .build())
                .setAutomaticTax(SessionCreateParams.AutomaticTax.builder()
                        .setEnabled(true)
                        .setLiability(SessionCreateParams.AutomaticTax.Liability.builder()
                                .setType(SessionCreateParams.AutomaticTax.Liability.Type.SELF)
                                .build())
                        .build())
                .setPaymentIntentData(SessionCreateParams.PaymentIntentData.builder()
                        .setApplicationFeeAmount(payment.getApplicationFeeAmount())
                        .setTransferData(SessionCreateParams.PaymentIntentData.TransferData.builder()
                                .setDestination(seller.getStripeAccountId())
                                .build())
                        .build())
                .addLineItem(SessionCreateParams.LineItem.builder()
                        .setQuantity(1L)
                        .setPriceData(SessionCreateParams.LineItem.PriceData.builder()
                                // Stripe speaks lowercase; the column is uppercase, and this is
                                // the boundary where the fold happens.
                                .setCurrency(request.currency().toLowerCase(Locale.ROOT))
                                .setUnitAmount(request.amount())
                                .setProductData(SessionCreateParams.LineItem.PriceData.ProductData.builder()
                                        .setName(request.description())
                                        .build())
                                .build())
                        .build())
                // Carried so that a person looking at the charge in Stripe's dashboard can tell
                // what it was for without this service.
                .putMetadata("payment_id", payment.getId().toString())
                .putMetadata("order_reference_type", request.orderReferenceType())
                .putMetadata("order_reference_id", request.orderReferenceId())
                .putMetadata("buyer_firebase_uid", request.buyerFirebaseUid())
                .putMetadata("seller_firebase_uid", request.sellerFirebaseUid())
                .build();
    }

    /**
     * The key for one order's payment.
     *
     * <p>Derived rather than asked for, because a caller that has to invent one will eventually
     * invent two for the same order - and the whole point is that one order has one payment.</p>
     *
     * @param request the request
     * @return the key
     */
    private String idempotencyKeyFor(OrderCheckoutRequest request) {
        return "order:%s:%s".formatted(request.orderReferenceType(), request.orderReferenceId());
    }

    private CheckoutSessionResponse response(Payment payment, String url) {
        return new CheckoutSessionResponse(
                payment.getId(),
                payment.getStripeCheckoutSessionId(),
                url,
                payment.getStatus(),
                payment.getAmount(),
                payment.getCurrency(),
                payment.getApplicationFeeAmount());
    }
}
