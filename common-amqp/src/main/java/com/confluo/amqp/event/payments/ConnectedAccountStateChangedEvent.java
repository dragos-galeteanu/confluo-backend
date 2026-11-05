package com.confluo.amqp.event.payments;

import com.confluo.amqp.event.DomainEvent;

import java.util.List;
import java.util.UUID;

/**
 * Stripe has changed what a seller is allowed to do.
 *
 * <p>One event for every transition rather than one per outcome, because the useful question is
 * which way it went: becoming payable and stopping being payable want opposite messages, and a
 * consumer holding both states can tell them apart without this service deciding for it.</p>
 *
 * <p>Published only when the state actually changes. {@code account.updated} fires at Stripe for
 * small things - a bank account added, a check finishing, a deadline approaching - and most of them
 * leave a seller exactly as payable as they were.</p>
 *
 * <p>{@code requirementsCurrentlyDue} is the actionable part, in Stripe's own words. It is what
 * turns "your account is restricted" into "Stripe still needs your identity document", which is
 * the difference between a seller who can act and one who contacts support.</p>
 *
 * @param connectedAccountId the account, which is also the envelope's aggregate id
 * @param firebaseUid whose it is
 * @param previousState what it could do before: {@code NOT_STARTED}, {@code RESTRICTED},
 *                      {@code ENABLED} or {@code REJECTED}
 * @param state what it can do now
 * @param payoutsEnabled whether money can reach their bank, which is the part sellers ask about
 * @param disabledReason Stripe's reason for withholding charges or payouts, or null
 * @param requirementsCurrentlyDue what Stripe still wants, as it names it
 */
@DomainEvent(aggregate = "account", event = "state_changed")
public record ConnectedAccountStateChangedEvent(
        UUID connectedAccountId,
        String firebaseUid,
        String previousState,
        String state,
        boolean payoutsEnabled,
        String disabledReason,
        List<String> requirementsCurrentlyDue) {
}
