package com.confluo.webhook.payload;

import com.confluo.common.mongo.repository.BaseMongoRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;


/**
 * Raw webhook bodies, by the event they belong to.
 *
 * <p>Always reached by {@code stripeEventId} rather than by the document's own identifier, which
 * nothing outside this collection ever holds - the same shape as the notifications service's
 * rendered messages.</p>
 */
@Repository
public interface StripeEventPayloadRepository extends BaseMongoRepository<StripeEventPayload> {

    /**
     * The body of one event.
     *
     * <p>Empty is not necessarily wrong. The ledger row in PostgreSQL is what must exist for an
     * event to be considered received; this document is the archive beside it, written in the same
     * handler but recoverable without. A ledger row whose body is missing means MongoDB was
     * unreachable when it arrived, which costs the evidence and not the event.</p>
     *
     * @param stripeEventId Stripe's {@code evt_} identifier
     * @return the body, or empty
     */
    Optional<StripeEventPayload> findByStripeEventId(String stripeEventId);

    /**
     * Whether a body has been archived, without loading it - which matters here, because loading
     * it means pulling kilobytes of JSON to answer a yes or no.
     *
     * @param stripeEventId Stripe's {@code evt_} identifier
     * @return true if the body is stored
     */
    boolean existsByStripeEventId(String stripeEventId);
}
