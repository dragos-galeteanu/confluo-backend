package com.confluo.amqp.consume;

import com.confluo.amqp.event.EventEnvelope;
import com.confluo.amqp.retry.RetryRepublishingErrorHandler;
import com.rabbitmq.client.Channel;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.listener.api.ChannelAwareMessageListener;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * What happens to one message: decode it, claim it, hand it over, acknowledge it.
 *
 * <p>Acknowledgement is manual, so each of the four outcomes is decided here rather than inferred
 * from whether a method returned:</p>
 *
 * <ul>
 *   <li><strong>Handled.</strong> Acknowledged, with the claim committed alongside whatever the
 *       handler did - one transaction, so the two cannot disagree.</li>
 *   <li><strong>Already consumed.</strong> Acknowledged without calling the handler. Publishing is
 *       at-least-once, so this is ordinary rather than exceptional.</li>
 *   <li><strong>Undecodable.</strong> Parked, and not retried: a message that cannot be read will
 *       not become readable.</li>
 *   <li><strong>The handler threw.</strong> Sent to its next retry tier, or parked once the
 *       attempts run out. The claim rolls back with the failure, so the retry is a real retry.</li>
 * </ul>
 *
 * <p>Nothing here rejects a message back onto its own queue. A requeued rejection is redelivered
 * immediately, so a message that always fails would spin a consumer at full speed; the waiting is
 * the broker's job, in a tier queue.</p>
 *
 * <p>While the handler runs, the producer's trace id is in the logging context, so a consumer's log
 * lines can be lined up with the request that caused the event. Only as a fallback: if tracing is
 * wired up, the listener container has already put the real span's trace there, and that one wins.</p>
 */
@Slf4j
public class EnvelopeMessageListener implements ChannelAwareMessageListener {

    private final Subscription subscription;
    private final MessageConverter converter;
    private final ConsumedMessageStore store;
    private final TransactionTemplate transaction;
    private final RetryRepublishingErrorHandler errorHandler;

    /** Where Micrometer's Brave bridge puts the current trace, and where handlers' logs read it. */
    private static final String TRACE_ID_KEY = "traceId";

    /**
     * @param subscription the handler and what it listens to
     * @param converter what turns a message back into an envelope
     * @param store the consumed-message ledger
     * @param transaction the transaction the claim and the handler share
     * @param errorHandler where a failed or unreadable message goes
     */
    public EnvelopeMessageListener(
            Subscription subscription,
            MessageConverter converter,
            ConsumedMessageStore store,
            TransactionTemplate transaction,
            RetryRepublishingErrorHandler errorHandler) {
        this.subscription = subscription;
        this.converter = converter;
        this.store = store;
        this.transaction = transaction;
        this.errorHandler = errorHandler;
    }

    @Override
    public void onMessage(Message message, Channel channel) throws Exception {
        long deliveryTag = message.getMessageProperties().getDeliveryTag();

        EventEnvelope<?> envelope;
        try {
            envelope = (EventEnvelope<?>) converter.fromMessage(message);
        } catch (RuntimeException e) {
            errorHandler.park(message, channel, subscription.queue(),
                    "an unreadable message", "could not be decoded: " + e.getMessage());
            return;
        }

        if (!subscription.payloadType().isInstance(envelope.payload())) {
            // Only reachable through a wildcard binding: the queue receives a family of events and
            // this handler declared one of them.
            log.warn("Ignoring {} on '{}': {} handles {}",
                    envelope.eventType(), subscription.queue(), subscription.name(),
                    subscription.payloadType().getSimpleName());
            channel.basicAck(deliveryTag, false);
            return;
        }

        try {
            Boolean handled = withTraceOf(envelope, () -> transaction.execute(status -> {
                if (!store.markConsumed(envelope.eventId(), subscription.name())) {
                    return false;
                }
                dispatch(envelope);
                return true;
            }));

            if (Boolean.FALSE.equals(handled)) {
                log.debug("Event {} has already been handled by {}",
                        envelope.eventId(), subscription.name());
            }
            channel.basicAck(deliveryTag, false);
        } catch (RuntimeException e) {
            errorHandler.onFailure(message, channel, subscription.queue(), describe(envelope), e);
        }
    }

    /**
     * Runs the work with the event's trace id in the logging context, if nothing else put one there.
     *
     * <p>Deliberately a fallback rather than an override. When tracing is configured the container
     * has already started a span whose trace is the producer's, propagated through the message
     * headers, and that is the one the logs should show. The envelope's copy is for the case where
     * nothing is tracing at all, or the headers were lost on the way.</p>
     *
     * @param envelope the decoded envelope
     * @param work what to run
     * @return whatever the work returned
     */
    private static Boolean withTraceOf(EventEnvelope<?> envelope, java.util.function.Supplier<Boolean> work) {
        if (envelope.traceId() == null || MDC.get(TRACE_ID_KEY) != null) {
            return work.get();
        }
        MDC.put(TRACE_ID_KEY, envelope.traceId());
        try {
            return work.get();
        } finally {
            MDC.remove(TRACE_ID_KEY);
        }
    }

    private String describe(EventEnvelope<?> envelope) {
        return "event %s (%s) for %s"
                .formatted(envelope.eventId(), envelope.eventType(), subscription.name());
    }

    /**
     * Hands the envelope to the handler.
     *
     * <p>The cast is unchecked because the handler's type parameter is only known at runtime, and
     * safe because the payload was just checked against it.</p>
     *
     * @param envelope the decoded envelope
     */
    @SuppressWarnings("unchecked")
    private void dispatch(EventEnvelope<?> envelope) {
        ((EventHandler<Object>) subscription.handler()).handle((EventEnvelope<Object>) envelope);
    }
}
