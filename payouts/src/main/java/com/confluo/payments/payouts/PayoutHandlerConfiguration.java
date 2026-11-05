package com.confluo.payments.payouts;

import com.confluo.payments.connect.ConnectedAccountRepository;
import com.confluo.payments.marketplace.PaymentRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers the handlers for money leaving.
 *
 * <p>Two classes rather than one, because the two movements genuinely differ: a transfer is created
 * by the platform and names the sale it funds, while a payout happens on the seller's own account
 * and names only a bank. They also arrive at <strong>different endpoints</strong> - transfers on
 * the platform's, payouts on the connected accounts' - which is worth knowing when a handler
 * mysteriously never fires.</p>
 *
 * <p>No events are published from either. A seller being paid is a fact they can already see in
 * Stripe's own dashboard, and a payout <em>failing</em> is worth telling them about - but that is a
 * notification this platform has nobody to send yet, and an event nothing consumes is a contract to
 * maintain for no one. It is the obvious next contract when there is a consumer.</p>
 */
@Configuration(proxyBeanMethods = false)
public class PayoutHandlerConfiguration {

    /**
     * A sale's share has moved onto a seller's Stripe balance.
     *
     * @param movements the mirrored movements
     * @param accounts the connected accounts
     * @param payments the mirrored payments
     * @return the handler
     */
    @Bean
    public TransferMirroringHandler transferCreatedHandler(
            PayoutTransferRepository movements,
            ConnectedAccountRepository accounts,
            PaymentRepository payments) {
        return new TransferMirroringHandler("transfer.created", movements, accounts, payments);
    }

    /**
     * A transfer was taken back, which is how the platform recovers its share of a refund or a
     * lost dispute.
     *
     * @param movements the mirrored movements
     * @param accounts the connected accounts
     * @param payments the mirrored payments
     * @return the handler
     */
    @Bean
    public TransferMirroringHandler transferReversedHandler(
            PayoutTransferRepository movements,
            ConnectedAccountRepository accounts,
            PaymentRepository payments) {
        return new TransferMirroringHandler("transfer.reversed", movements, accounts, payments);
    }

    /**
     * A payout has been created and is on its way to a seller's bank.
     *
     * @param movements the mirrored movements
     * @param accounts the connected accounts
     * @return the handler
     */
    @Bean
    public PayoutMirroringHandler payoutCreatedHandler(
            PayoutTransferRepository movements, ConnectedAccountRepository accounts) {
        return new PayoutMirroringHandler("payout.created", movements, accounts);
    }

    /**
     * A payout has arrived. The one a seller is waiting for.
     *
     * @param movements the mirrored movements
     * @param accounts the connected accounts
     * @return the handler
     */
    @Bean
    public PayoutMirroringHandler payoutPaidHandler(
            PayoutTransferRepository movements, ConnectedAccountRepository accounts) {
        return new PayoutMirroringHandler("payout.paid", movements, accounts);
    }

    /**
     * A payout did not arrive.
     *
     * <p>The money stays on the seller's Stripe balance, so nothing is lost - but their bank
     * details are usually wrong, and only they can fix that.</p>
     *
     * @param movements the mirrored movements
     * @param accounts the connected accounts
     * @return the handler
     */
    @Bean
    public PayoutMirroringHandler payoutFailedHandler(
            PayoutTransferRepository movements, ConnectedAccountRepository accounts) {
        return new PayoutMirroringHandler("payout.failed", movements, accounts);
    }

    /**
     * A payout was cancelled before it left.
     *
     * @param movements the mirrored movements
     * @param accounts the connected accounts
     * @return the handler
     */
    @Bean
    public PayoutMirroringHandler payoutCanceledHandler(
            PayoutTransferRepository movements, ConnectedAccountRepository accounts) {
        return new PayoutMirroringHandler("payout.canceled", movements, accounts);
    }
}
