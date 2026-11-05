package com.confluo.notifications.listener;

import com.confluo.amqp.event.EventEnvelope;
import com.confluo.notifications.channel.Channel;
import com.confluo.notifications.dto.notification.NotificationSendRequest;
import com.confluo.notifications.service.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;

/**
 * What every listener for a payments event needs, so none of them writes it again.
 *
 * <p>The producing service is {@code payments} for all of them, and the send is always the same
 * shape: a named recipient, a template, some variables, and the envelope's event id so the message
 * can be traced back to what caused it.</p>
 *
 * <h2>Why the events carry no email address</h2>
 *
 * <p>A payments event names a person by their Firebase uid and nothing else. This service then
 * resolves the address itself - from its own {@code recipients} table, or from Firebase if it has
 * never heard of them. That is the arrangement {@code Recipient} was built for: contact details
 * carried inside every event contract would go stale sitting in an outbox, and the copy here is
 * kept current by the service that owns users.</p>
 *
 * <h2>The category is the template's, not the listener's</h2>
 *
 * <p>Nothing here declares whether a message is transactional. {@code NotificationSendRequest} has
 * no category field on purpose - the template declares it, so that a caller cannot make their own
 * marketing undeclinable. Which means the templates these listeners name have to be created with
 * the right category, and a receipt declared {@code MARKETING} would be silently switchable off by
 * anybody who had opted out.</p>
 */
@RequiredArgsConstructor
@Slf4j
public abstract class PaymentEventNotifier {

    /** The service that publishes every event these listeners consume. */
    protected static final String PRODUCER = "payments";

    protected final NotificationService notifications;

    /**
     * Sends one message about one event.
     *
     * <p>Failures are <strong>not</strong> swallowed. A handler that throws tells common-amqp the
     * work did not happen, which rolls its transaction back and retries the message - and that is
     * the behaviour wanted here: somebody who did not get their receipt should get it on the
     * retry rather than never.</p>
     *
     * @param envelope the event that caused this
     * @param firebaseUid who the message is for
     * @param templateKey which message to send
     * @param dedupKey what must not be sent twice ever, or null for a message that may legitimately
     *                 be sent again
     * @param variables what to fill the template in with
     */
    protected void send(
            EventEnvelope<?> envelope,
            String firebaseUid,
            String templateKey,
            String dedupKey,
            Map<String, Object> variables) {

        NotificationSendRequest request = new NotificationSendRequest(
                Channel.EMAIL,
                templateKey,
                firebaseUid,
                // No contact details: this service resolves them. See the class javadoc.
                null,
                null,
                null,
                null,
                null,
                variables,
                dedupKey,
                // Ties the message to whatever conversation the event belonged to, which is how a
                // receipt and the charge it is for end up in one trace.
                envelope.correlationId());

        notifications.send(request, envelope.source(), envelope.eventId());
        log.info("Queued {} for {} from event {} [{}]",
                templateKey, firebaseUid, envelope.eventType(), envelope.eventId());
    }
}
