package com.confluo.payments.connect;

import com.confluo.common.exception.ResourceNotFoundException;
import com.confluo.payments.connect.dto.ConnectAccountCreateRequest;
import com.confluo.payments.connect.dto.ConnectAccountResponse;
import com.confluo.payments.connect.dto.OnboardingLinkResponse;
import com.confluo.stripe.ConnectAccountGateway;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;


/**
 * Gets a seller to the point where they can be paid.
 *
 * <p>Which is Stripe's work, not this platform's. A seller's legal name, date of birth, address,
 * bank account and identity document are collected on Stripe's own hosted pages and never pass
 * through here - so this service's whole job is to create the account, hand out links to those
 * pages, and record what Stripe decides.</p>
 *
 * <p><strong>Nothing here decides whether a seller is verified.</strong> Every flag on a
 * {@link ConnectedAccount} is written by the {@code account.updated} webhook. This service creates
 * the row with everything false, and if Stripe never says otherwise it stays that way - which is
 * the correct failure, because {@code OrderCheckoutService} refuses to sell for an account that
 * cannot be charged.</p>
 *
 * <p>Not {@code @Transactional} at the class level: the writes call Stripe, and holding a database
 * connection across a network round trip is what the rest of this service avoids too.</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ConnectOnboardingService {

    private final ConnectedAccountRepository repository;
    private final ConnectAccountGateway gateway;
    private final ConnectProperties properties;

    /**
     * Creates a seller's account, or returns the one they already have.
     *
     * <p>Idempotent on the seller, twice over: this looks for an existing row first, and the
     * gateway's idempotency key means even a race that gets past that check cannot give one person
     * two Stripe accounts. Which matters more here than almost anywhere else - a duplicate account
     * is a second identity verification, a second bank account, and payouts going somewhere the
     * seller is not expecting.</p>
     *
     * @param request who, and where they are
     * @return their account
     */
    public ConnectAccountResponse createAccount(ConnectAccountCreateRequest request) {
        Optional<ConnectedAccount> existing =
                repository.findByFirebaseUid(request.firebaseUid());
        if (existing.isPresent()) {
            log.debug("Seller {} already has a connected account", request.firebaseUid());
            return respond(existing.get());
        }

        ConnectAccountGateway.ConnectedAccountSnapshot snapshot = gateway.create(
                request.firebaseUid(), request.country(), request.defaultCurrency(), request.email());

        ConnectedAccount account = new ConnectedAccount();
        account.setFirebaseUid(request.firebaseUid());
        account.setStripeAccountId(snapshot.stripeAccountId());
        // From Stripe's answer rather than the request: Stripe is the one that fixed them, and if
        // it normalised or refused something the row should say what the account actually is.
        account.setCountry(snapshot.country() == null ? request.country() : snapshot.country());
        account.setDefaultCurrency(snapshot.defaultCurrency() == null
                ? request.defaultCurrency()
                : snapshot.defaultCurrency());
        // Null: no webhook has arrived, so there is no Stripe timestamp to record. See applyTo.
        applyTo(account, snapshot, null);

        try {
            ConnectedAccount saved = repository.save(account);
            log.info("Connected account recorded [id={}, uid={}, account={}, state={}]",
                    saved.getId(), saved.getFirebaseUid(), saved.getStripeAccountId(),
                    saved.getState());
            return respond(saved);
        } catch (DataIntegrityViolationException e) {
            // Two requests for the same new seller at once. The idempotency key means both got the
            // same Stripe account, so the winner's row is the right one to return.
            log.debug("Connected account for {} was recorded concurrently", request.firebaseUid());
            return respond(repository.findByFirebaseUid(request.firebaseUid()).orElseThrow(() -> e));
        }
    }

    /**
     * A fresh link to Stripe's onboarding, for a seller who needs one.
     *
     * <p>A route of its own rather than something returned once at creation, because an account
     * link is single-use and expires in minutes. A seller who abandons the form, comes back
     * tomorrow, or follows Stripe's own refresh URL needs a new one - and there is no way to reuse
     * the old.</p>
     *
     * <p>The <strong>kind</strong> of link depends on where they have got to: somebody who has not
     * submitted their details gets first-time onboarding, and somebody who has gets the update
     * form, which is what they need to fix a requirement Stripe has since raised. Handing a
     * seller the wrong one shows them a form they have already filled in.</p>
     *
     * @param firebaseUid whose account
     * @return the link
     * @throws ResourceNotFoundException if they have no account yet
     */
    public OnboardingLinkResponse createOnboardingLink(String firebaseUid) {
        ConnectedAccount account = getEntityByFirebaseUid(firebaseUid);
        boolean accountUpdate = account.isDetailsSubmitted();

        ConnectAccountGateway.OnboardingLink link =
                gateway.createOnboardingLink(
                        account.getStripeAccountId(),
                        accountUpdate,
                        properties.onboardingReturnUrl(),
                        properties.onboardingRefreshUrl());

        log.info("Onboarding link issued [uid={}, account={}, kind={}, expiresAt={}]",
                firebaseUid, account.getStripeAccountId(),
                accountUpdate ? "account_update" : "account_onboarding", link.expiresAt());
        return new OnboardingLinkResponse(link.url(), link.expiresAt(), accountUpdate);
    }

    /**
     * The account belonging to a seller.
     * @param firebaseUid whose account
     * @return the account
     * @throws ResourceNotFoundException if they have none
     */
    @Transactional(readOnly = true)
    public ConnectedAccount getEntityByFirebaseUid(String firebaseUid) {
        return repository.findByFirebaseUid(firebaseUid)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Seller %s has no connected account".formatted(firebaseUid)));
    }

    /**
     * A seller's account, as the API returns it.
     * @param firebaseUid whose account
     * @return the account
     */
    @Transactional(readOnly = true)
    public ConnectAccountResponse getByFirebaseUid(String firebaseUid) {
        return respond(getEntityByFirebaseUid(firebaseUid));
    }

    /**
     * The sellers Stripe is still waiting on, which is what an onboarding reminder is sent from.
     * @return their accounts
     */
    @Transactional(readOnly = true)
    public List<ConnectAccountResponse> getAwaitingRequirements() {
        return repository.findByDetailsSubmittedTrueAndPayoutsEnabledFalse().stream()
                .map(this::respond)
                .toList();
    }

    /**
     * Reads an account's state from Stripe and writes it down.
     *
     * <p>The repair for a row whose {@code account.updated} never arrived or failed to apply. The
     * webhook is the ordinary path and this is not a substitute for it: polling every account would
     * be a great many calls to learn nothing, which is exactly what webhooks exist to avoid.</p>
     *
     * <p>It takes Stripe's answer unconditionally - no timestamp guard - because unlike an event,
     * what Stripe says right now cannot be stale.</p>
     *
     * @param firebaseUid whose account
     * @return the account, as Stripe currently has it
     */
    public ConnectAccountResponse refreshFromStripe(String firebaseUid) {
        ConnectedAccount account = getEntityByFirebaseUid(firebaseUid);
        ConnectAccountGateway.ConnectedAccountSnapshot snapshot =
                gateway.retrieve(account.getStripeAccountId());

        applyTo(account, snapshot, Instant.now());
        ConnectedAccount saved = repository.save(account);

        log.info("Connected account refreshed from Stripe [uid={}, account={}, state={}]",
                firebaseUid, saved.getStripeAccountId(), saved.getState());
        return respond(saved);
    }

    /**
     * Writes Stripe's view of an account onto the row.
     *
     * <p>Static and public so the {@code account.updated} handler uses exactly this, rather than a
     * second copy that eventually disagrees with it. {@code onboardingCompletedAt} is set once and
     * never cleared: it is when they first finished, which does not stop being true if Stripe later
     * asks for something else.</p>
     *
     * <p>{@code eventAt} is null when nothing has been learned from Stripe yet - which is the case
     * the moment an account is created. {@code lastAccountEventAt} is then left null, so the first
     * {@code account.updated} to arrive is applied rather than being compared against a timestamp
     * this service invented. Writing the local clock there instead would drop that first event
     * whenever Stripe's clock was a moment behind, and that event is the one that makes a seller
     * payable.</p>
     *
     * @param account the row
     * @param snapshot what Stripe says
     * @param eventAt when Stripe said it, or null if this is not from Stripe telling us
     */
    public static void applyTo(
            ConnectedAccount account,
            ConnectAccountGateway.ConnectedAccountSnapshot snapshot,
            Instant eventAt) {

        account.setDetailsSubmitted(snapshot.detailsSubmitted());
        account.setChargesEnabled(snapshot.chargesEnabled());
        account.setPayoutsEnabled(snapshot.payoutsEnabled());
        account.setDisabledReason(snapshot.disabledReason());
        account.setRequirementsCurrentlyDue(new ArrayList<>(snapshot.currentlyDue()));
        account.setRequirementsPastDue(new ArrayList<>(snapshot.pastDue()));

        if (snapshot.detailsSubmitted() && account.getOnboardingCompletedAt() == null) {
            account.setOnboardingCompletedAt(eventAt == null ? Instant.now() : eventAt);
        }
        if (eventAt != null) {
            account.setLastAccountEventAt(eventAt);
        }
    }

    private ConnectAccountResponse respond(ConnectedAccount account) {
        return new ConnectAccountResponse(
                account.getId(),
                account.getFirebaseUid(),
                account.getStripeAccountId(),
                account.getCountry(),
                account.getDefaultCurrency(),
                account.getState(),
                account.isDetailsSubmitted(),
                account.isChargesEnabled(),
                account.isPayoutsEnabled(),
                account.getDisabledReason(),
                account.getRequirementsCurrentlyDue(),
                account.getRequirementsPastDue(),
                account.getOnboardingCompletedAt(),
                account.getLastAccountEventAt());
    }
}
