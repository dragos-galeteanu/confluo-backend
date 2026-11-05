package com.confluo.webhook.payload;

import com.confluo.common.mongo.entity.BaseMongoDocument;
import lombok.Getter;
import lombok.Setter;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * The body of one webhook, exactly as Stripe sent it.
 *
 * <p>This is the only record of what Stripe actually said. Everything else this service holds about
 * an event is an interpretation of it - the ledger row, the mirrored payment, the subscription's
 * status - so when one of those looks wrong, this is the document that settles whether the service
 * misread the event or the event said something unexpected.</p>
 *
 * <p><strong>{@link #payload} is a string, not a parsed document, and that is the whole point.</strong>
 * The signature was computed over exactly those bytes. Parsing the JSON and storing the result
 * would reorder keys and normalise whitespace, producing something that can no longer be verified
 * against {@link #signatureHeader} and so can no longer be proved to have come from Stripe. Stored
 * verbatim, the pair is replayable: the body and its signature are everything the endpoint
 * received, and feeding them back through the verifier is a faithful rerun.</p>
 *
 * <p>It also means this collection is not queried by content. Nothing needs to be - the columns
 * worth searching are on the ledger row in PostgreSQL, and this is read by event id, once, by
 * somebody looking into a specific problem.</p>
 *
 * <p>{@code createdAt} is the moment the body was received, because the document is written on
 * arrival. There is deliberately no TTL index on it yet: expiring these is a decision about
 * discarding the only evidence of what Stripe said, and the right retention depends on how long
 * disputes and chargebacks stay open - so it belongs in a deliberate change rather than as a
 * default nobody chose. {@code deletedAt} is how a retention policy will reach them.</p>
 */
@Getter
@Setter
@Document(collection = "stripe_event_payloads")
public class StripeEventPayload extends BaseMongoDocument {

    /**
     * Stripe's {@code evt_} identifier, which is how this is reached and the only index on the
     * collection. Unique: one event has one body, and a second would mean the endpoint stored a
     * redelivery as though it were new - which is exactly what the ledger exists to prevent.
     */
    @Indexed(unique = true)
    private String stripeEventId;

    /** what happened, copied from the body so this document can be read on its own */
    private String eventType;

    /**
     * The {@code Stripe-Signature} header as it arrived. Kept with the body because neither proves
     * anything without the other, and together they can be re-verified years later.
     */
    private String signatureHeader;

    /** the raw request body, byte for byte */
    private String payload;
}
