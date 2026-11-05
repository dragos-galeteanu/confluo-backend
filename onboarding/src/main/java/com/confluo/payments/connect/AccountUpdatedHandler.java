package com.confluo.payments.connect;

import com.confluo.amqp.event.payments.ConnectedAccountStateChangedEvent;
import com.confluo.amqp.publish.EventPublisher;
import com.confluo.webhook.StripeEvent;
import com.confluo.webhook.StripeEventHandler;
import com.confluo.stripe.ConnectAccountGateway;
import com.stripe.model.Account;
import com.stripe.model.Event;
import com.stripe.model.StripeObject;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;


/**
 * Stripe has changed its mind about a seller.
 *
 * <p><strong>The only thing that decides whether a seller can be paid.</strong> Every flag on a
 * {@link ConnectedAccount} is written here and nowhere else; the onboarding service creates the row
 * with everything false and never sets any of it. So a platform that drops this event is a platform
 * where no seller ever becomes payable - and one that applies it wrongly is a platform that sells
 * on behalf of somebody Stripe has restricted.</p>
 *
 * <p>It arrives on the <strong>Connect</strong> webhook endpoint, verified with that endpoint's own
 * signing secret. That is the practical reason the two endpoints exist: a platform with only the
 * first one never hears about its sellers at all.</p>
 *
 * <p>This event fires often and for small things - a seller adding a bank account, Stripe finishing
 * a check, a requirement appearing as a deadline approaches. Most carry no change this service
 * cares about, which is why the interesting transitions are logged and the rest are not.</p>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AccountUpdatedHandler implements StripeEventHandler {

    private final ConnectedAccountRepository accounts;
    private final EventPublisher events;

    @Override
    public String eventType() {
        return "account.updated";
    }

    @Override
    public void handle(StripeEvent ledgerRow, Event event) {
        Account stripeAccount = accountOf(event);

        Optional<ConnectedAccount> found = accounts.findByStripeAccountId(stripeAccount.getId());
        if (found.isEmpty()) {
            // Reachable, and not an error: an account created in Stripe's dashboard, or one whose
            // creation here did not commit. Retrying cannot conjure a row, so the event is applied
            // as a no-op with the id in the log - the same treatment an unknown payment intent gets.
            log.warn("Stripe reported account.updated for {}, which this service does not have",
                    stripeAccount.getId());
            return;
        }

        ConnectedAccount account = found.get();
        if (isStale(ledgerRow, account.getLastAccountEventAt())) {
            log.info("Ignoring Stripe event {} for account {}: it occurred at {}, and {} has "
                            + "already been applied",
                    ledgerRow.getStripeEventId(), stripeAccount.getId(),
                    ledgerRow.getEventCreatedAt(), account.getLastAccountEventAt());
            return;
        }

        ConnectedAccountState before = account.getState();

        ConnectOnboardingService.applyTo(account, snapshot(stripeAccount),
                ledgerRow.getEventCreatedAt() == null ? Instant.now() : ledgerRow.getEventCreatedAt());
        ConnectedAccount saved = accounts.save(account);

        ConnectedAccountState after = saved.getState();
        if (before != after) {
            // The transition, and the only thing here worth announcing: a seller becoming payable,
            // or stopping being payable, is what anybody watching this platform wants to know.
            // account.updated fires for a great deal that changes neither.
            java.util.UUID eventId = events.publish(saved.getId(),
                    new ConnectedAccountStateChangedEvent(
                            saved.getId(),
                            saved.getFirebaseUid(),
                            before.name(),
                            after.name(),
                            saved.isPayoutsEnabled(),
                            saved.getDisabledReason(),
                            saved.getRequirementsCurrentlyDue()));
            log.info("Connected account state changed [uid={}, account={}, {} -> {}, due={}, "
                            + "event={}]",
                    saved.getFirebaseUid(), saved.getStripeAccountId(), before, after,
                    saved.getRequirementsCurrentlyDue(), eventId);
        } else {
            log.debug("Connected account updated with no change of state [uid={}, state={}]",
                    saved.getFirebaseUid(), after);
        }
    }

    /**
     * Stripe's account as the shape {@link ConnectOnboardingService#applyTo} writes from.
     *
     * <p>Built here rather than shared with {@code StripeConnectAccountGateway}, which maps the
     * same fields: that one maps the result of a call this service made, and this one maps an event
     * Stripe sent. They happen to read the same fields today, and tying the webhook's parsing to
     * the gateway's would make an interface the webhook does not go through.</p>
     *
     * @param account what Stripe sent
     * @return the snapshot
     */
    private ConnectAccountGateway.ConnectedAccountSnapshot snapshot(Account account) {
        Account.Requirements requirements = account.getRequirements();
        return new ConnectAccountGateway.ConnectedAccountSnapshot(
                account.getId(),
                account.getCountry() == null ? null : account.getCountry().toUpperCase(Locale.ROOT),
                account.getDefaultCurrency() == null
                        ? null
                        : account.getDefaultCurrency().toUpperCase(Locale.ROOT),
                Boolean.TRUE.equals(account.getDetailsSubmitted()),
                Boolean.TRUE.equals(account.getChargesEnabled()),
                Boolean.TRUE.equals(account.getPayoutsEnabled()),
                requirements == null ? null : requirements.getDisabledReason(),
                requirements == null || requirements.getCurrentlyDue() == null
                        ? List.of()
                        : List.copyOf(requirements.getCurrentlyDue()),
                requirements == null || requirements.getPastDue() == null
                        ? List.of()
                        : List.copyOf(requirements.getPastDue()));
    }

    private boolean isStale(StripeEvent ledgerRow, Instant lastApplied) {
        return lastApplied != null
                && ledgerRow.getEventCreatedAt() != null
                && !ledgerRow.getEventCreatedAt().isAfter(lastApplied);
    }

    private Account accountOf(Event event) {
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
        if (!(object instanceof Account account)) {
            throw new IllegalStateException(
                    "Stripe event %s (%s) carries a %s, not an Account".formatted(
                            event.getId(), event.getType(), object.getClass().getSimpleName()));
        }
        return account;
    }
}
