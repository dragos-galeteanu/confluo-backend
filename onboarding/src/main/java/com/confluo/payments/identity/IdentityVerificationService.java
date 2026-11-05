package com.confluo.payments.identity;

import com.confluo.common.exception.ResourceNotFoundException;
import com.confluo.payments.customer.StripeCustomerRepository;
import com.confluo.payments.identity.dto.IdentityVerificationLinkResponse;
import com.confluo.payments.identity.dto.IdentityVerificationResponse;
import com.confluo.stripe.IdentityVerificationGateway;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;
import java.util.Optional;


/**
 * Whether somebody is who they say they are, as far as Stripe is concerned.
 *
 * <p><strong>Deliberately separate from Connect.</strong> Identity checks a person's documents;
 * Connect decides whether an account may be paid. They look alike and are not: a seller can pass
 * identity verification and still be unable to receive money because their bank details are
 * missing, and conflating the two leaves nobody able to tell which half is the problem. Nothing in
 * this service touches a {@code ConnectedAccount}.</p>
 *
 * <p>One row per attempt, not per person. A failed check is retried, and what failed the first time
 * is the only explanation of what to tell them - so the history is kept and "are they verified" is
 * answered by the latest.</p>
 *
 * <p>Not {@code @Transactional} at the class level: the writes call Stripe, as everywhere else
 * here.</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class IdentityVerificationService {

    private final IdentityVerificationRepository repository;
    private final StripeCustomerRepository customers;
    private final IdentityVerificationGateway gateway;
    private final IdentityProperties properties;

    /**
     * Starts a check, or picks up the one already under way.
     *
     * <p>Which of the four it does depends on where the person's latest attempt got to, and each
     * branch is a different thing to tell them:</p>
     *
     * <ul>
     *   <li><strong>nothing yet, or the last was cancelled</strong> - start a new session;</li>
     *   <li><strong>awaiting their documents</strong> - resume it. The URL handed out before cannot
     *       be assumed to still be in their hands, so a fresh one is read from Stripe rather than
     *       stored and reissued;</li>
     *   <li><strong>Stripe is still checking</strong> - nothing for them to do, and no URL.
     *       Starting a second session here would ask them to submit documents they have already
     *       submitted;</li>
     *   <li><strong>already verified</strong> - refused. Re-verifying somebody who has passed is a
     *       deliberate act rather than something a retry should do by accident.</li>
     * </ul>
     *
     * @param firebaseUid whose identity
     * @return where to send them, or the status that means there is nowhere to send them
     * @throws IllegalStateException if they are already verified
     */
    public IdentityVerificationLinkResponse start(String firebaseUid) {
        Optional<IdentityVerification> latest =
                repository.findFirstByFirebaseUidOrderByCreatedAtDesc(firebaseUid);

        if (latest.isPresent()) {
            IdentityVerification attempt = latest.get();
            switch (attempt.getStatus()) {
                case VERIFIED -> throw new IllegalStateException(
                        "%s has already been verified; starting another check is a deliberate act"
                                .formatted(firebaseUid));
                case PROCESSING -> {
                    log.debug("Identity check for {} is still with Stripe", firebaseUid);
                    return new IdentityVerificationLinkResponse(
                            null, IdentityVerificationStatus.PROCESSING, true);
                }
                case REQUIRES_INPUT -> {
                    return resume(attempt);
                }
                case CANCELED -> {
                    // Terminal at Stripe, so there is nothing to resume: a new session it is.
                }
            }
        }

        return startNew(firebaseUid);
    }

    private IdentityVerificationLinkResponse resume(IdentityVerification attempt) {
        IdentityVerificationGateway.VerificationSessionSnapshot snapshot =
                gateway.retrieve(attempt.getStripeVerificationSessionId());

        applyTo(attempt, snapshot);
        IdentityVerification saved = repository.save(attempt);

        log.info("Identity check resumed [uid={}, session={}, status={}]",
                saved.getFirebaseUid(), saved.getStripeVerificationSessionId(), saved.getStatus());
        return new IdentityVerificationLinkResponse(snapshot.url(), saved.getStatus(), true);
    }

    private IdentityVerificationLinkResponse startNew(String firebaseUid) {
        String stripeCustomerId = customers.findByFirebaseUid(firebaseUid)
                .map(customer -> customer.getStripeCustomerId())
                .orElse(null);

        IdentityVerificationGateway.VerificationSessionSnapshot snapshot =
                gateway.create(firebaseUid, stripeCustomerId, properties.returnUrl());

        IdentityVerification attempt = new IdentityVerification();
        attempt.setFirebaseUid(firebaseUid);
        attempt.setStripeVerificationSessionId(snapshot.stripeVerificationSessionId());
        applyTo(attempt, snapshot);
        IdentityVerification saved = repository.save(attempt);

        log.info("Identity check started [uid={}, session={}, status={}]",
                firebaseUid, saved.getStripeVerificationSessionId(), saved.getStatus());
        return new IdentityVerificationLinkResponse(snapshot.url(), saved.getStatus(), false);
    }

    /**
     * Somebody's latest attempt.
     * @param firebaseUid whose identity
     * @return the attempt
     * @throws ResourceNotFoundException if they have never attempted one
     */
    @Transactional(readOnly = true)
    public IdentityVerificationResponse getLatest(String firebaseUid) {
        return respond(repository.findFirstByFirebaseUidOrderByCreatedAtDesc(firebaseUid)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "%s has not attempted identity verification".formatted(firebaseUid))));
    }

    /**
     * Whether somebody has passed.
     *
     * <p>The question other services actually ask, answered from the latest attempt rather than
     * from whether any attempt ever succeeded - because a verification can be superseded, and
     * "they passed once" is not the same as "they are verified".</p>
     *
     * @param firebaseUid whose identity
     * @return true if their latest attempt is verified
     */
    @Transactional(readOnly = true)
    public boolean isVerified(String firebaseUid) {
        return repository.findFirstByFirebaseUidOrderByCreatedAtDesc(firebaseUid)
                .map(attempt -> attempt.getStatus() == IdentityVerificationStatus.VERIFIED)
                .orElse(false);
    }

    /**
     * Writes Stripe's view of a session onto the row.
     *
     * <p>Static and used by the webhook handler as well, so the two cannot drift into writing
     * different things - the same arrangement {@code ConnectOnboardingService.applyTo} has.</p>
     *
     * <p>{@code verifiedAt} is set once. Stripe will mention a verified session again; the moment
     * they became satisfied does not move.</p>
     *
     * @param attempt the row
     * @param snapshot what Stripe says
     */
    public static void applyTo(
            IdentityVerification attempt,
            IdentityVerificationGateway.VerificationSessionSnapshot snapshot) {

        attempt.setStatus(statusOf(snapshot.status()));
        attempt.setLastErrorCode(snapshot.errorCode());
        attempt.setLastErrorReason(snapshot.errorReason());

        if (attempt.getStatus() == IdentityVerificationStatus.VERIFIED
                && attempt.getVerifiedAt() == null) {
            attempt.setVerifiedAt(java.time.Instant.now());
        }
    }

    /**
     * Stripe's session status as this service's own.
     *
     * <p>Throws on anything unrecognised rather than defaulting, for the reason the subscription
     * and invoice handlers do: the plausible defaults are "verified" and "not verified", and
     * guessing the first would let somebody through an identity check Stripe has not passed.</p>
     *
     * @param status Stripe's own value
     * @return the mapped status
     */
    public static IdentityVerificationStatus statusOf(String status) {
        if (status == null) {
            throw new IllegalStateException(
                    "A Stripe verification session with no status cannot be applied");
        }
        try {
            return IdentityVerificationStatus.valueOf(status.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(
                    "Stripe reported verification status '%s', which this service does not know; "
                            .formatted(status) + "it is left unapplied rather than guessed at");
        }
    }

    private IdentityVerificationResponse respond(IdentityVerification attempt) {
        return new IdentityVerificationResponse(
                attempt.getId(),
                attempt.getFirebaseUid(),
                attempt.getStripeVerificationSessionId(),
                attempt.getStatus(),
                attempt.getVerifiedAt(),
                attempt.getLastErrorCode(),
                attempt.getLastErrorReason(),
                attempt.getCreatedAt());
    }
}
