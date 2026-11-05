package com.confluo.payments.customer;

import com.confluo.stripe.StripeOperationException;
import com.stripe.StripeClient;
import com.stripe.exception.StripeException;
import com.stripe.model.Customer;
import com.stripe.net.RequestOptions;
import com.stripe.param.CustomerCreateParams;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/**
 * Gives a person a Stripe customer, once.
 *
 * <p>A customer is needed before a payer can be charged in a way Stripe Tax can work with: the tax
 * calculation wants an address, Checkout collects one, and the customer is where it is kept so the
 * next purchase does not ask again. It is also what a subscription has to be attached to.</p>
 *
 * <p>Deliberately not {@code @Transactional}. The Stripe call is a network round trip, and holding
 * a database connection open across it is how a connection pool runs out under load. The three
 * steps are each atomic on their own, and the failure between them is handled rather than
 * prevented - see below.</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class StripeCustomerService {

    private final StripeCustomerRepository repository;
    private final StripeClient stripe;

    /**
     * The Stripe customer for a Firebase uid, creating one if this service has never made it.
     *
     * <p>The idempotency key is what makes the gap between creating the customer at Stripe and
     * storing the row survivable. If the row insert fails, or the process dies between the two, a
     * retry sends the same key and Stripe hands back the customer it already made rather than a
     * second one - so the worst case is a customer that exists at Stripe and is picked up on the
     * next attempt, not a person with two.</p>
     *
     * <p>The caught constraint violation is the other half: two requests for the same new person at
     * the same instant both create nothing extra at Stripe, thanks to the key, and one of them
     * loses the insert and re-reads the winner's row.</p>
     *
     * @param firebaseUid whose customer it is
     * @return the mapping
     * @throws StripeException if Stripe refuses to create the customer
     */
    public StripeCustomer resolveOrCreate(String firebaseUid) throws StripeException {
        return repository.findByFirebaseUid(firebaseUid)
                .orElseGet(() -> create(firebaseUid));
    }

    private StripeCustomer create(String firebaseUid) {
        Customer created;
        try {
            created = stripe.customers().create(
                    CustomerCreateParams.builder()
                            // The only thing Stripe is told about them. An address and an email
                            // arrive from Checkout, which collects both and writes them back.
                            .putMetadata("firebase_uid", firebaseUid)
                            .build(),
                    RequestOptions.builder()
                            .setIdempotencyKey("customer:" + firebaseUid)
                            .build());
        } catch (StripeException e) {
            throw new StripeOperationException(
                    "Could not create a Stripe customer for %s".formatted(firebaseUid), e);
        }

        StripeCustomer mapping = new StripeCustomer();
        mapping.setFirebaseUid(firebaseUid);
        mapping.setStripeCustomerId(created.getId());
        try {
            StripeCustomer saved = repository.save(mapping);
            log.info("Stripe customer created [uid={}, customer={}]", firebaseUid, created.getId());
            return saved;
        } catch (DataIntegrityViolationException e) {
            // Somebody else got there first. Their row is the one to use, and the idempotency key
            // means they are pointing at the same Stripe customer this call just received.
            log.debug("Stripe customer for {} was created concurrently", firebaseUid);
            return repository.findByFirebaseUid(firebaseUid).orElseThrow(() -> e);
        }
    }
}
