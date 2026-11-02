package com.confluo.amqp.publish;

import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.ReturnedMessage;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Publishes one message and only returns once the broker has said it took it.
 *
 * <p>Used by both halves of the module, which is why it is separate from either: the drainer sends
 * events out of the outbox, and the consumer's error handler sends failed messages to a retry tier
 * or a parking lot. Both are about to do something irreversible on the strength of the publish
 * having worked - mark an entry published, or acknowledge the original message - so neither can
 * afford a fire-and-forget send.</p>
 *
 * <p>Three things are therefore checked rather than assumed:</p>
 *
 * <ul>
 *   <li>A <strong>publisher confirm</strong>, because without one a send reports success whether or
 *       not the broker ever accepted the message.</li>
 *   <li>A <strong>return</strong>, because a message published to a routing key nothing is bound to
 *       is discarded, and with {@code mandatory} set it comes back first. Otherwise a message that
 *       went nowhere is indistinguishable from one that was delivered.</li>
 *   <li>A <strong>timeout</strong>, because a broker that accepts a connection and then goes quiet
 *       would hold the caller open indefinitely.</li>
 * </ul>
 */
public class ConfirmedPublisher {

    private final RabbitTemplate template;
    private final Duration confirmTimeout;

    /**
     * @param template the publishing template, with confirms and returns turned on
     * @param confirmTimeout how long to wait to be told
     */
    public ConfirmedPublisher(RabbitTemplate template, Duration confirmTimeout) {
        this.template = template;
        this.confirmTimeout = confirmTimeout;
    }

    /**
     * Publishes a message and waits for the broker to confirm it.
     *
     * @param exchange where to publish
     * @param routingKey the key to publish with
     * @param message the message
     * @param correlationId what to call this publish in a failure message, usually the event's id
     * @throws EventPublishException if the broker did not take it, sent it back, or said nothing
     */
    public void publish(String exchange, String routingKey, Message message, String correlationId) {
        CorrelationData correlation = new CorrelationData(correlationId);
        try {
            template.send(exchange, routingKey, message, correlation);
            awaitConfirm(exchange, routingKey, correlationId, correlation);
        } catch (AmqpException e) {
            throw new EventPublishException(
                    "Could not publish %s to '%s' with key '%s': %s"
                            .formatted(correlationId, exchange, routingKey, e.getMessage()), e);
        }
    }

    private void awaitConfirm(
            String exchange, String routingKey, String correlationId, CorrelationData correlation) {
        CorrelationData.Confirm confirm;
        try {
            confirm = correlation.getFuture().get(confirmTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new EventPublishException(
                    "Interrupted waiting for the broker to confirm %s".formatted(correlationId), e);
        } catch (TimeoutException e) {
            throw new EventPublishException(
                    "The broker did not confirm %s within %s".formatted(correlationId, confirmTimeout), e);
        } catch (ExecutionException e) {
            throw new EventPublishException(
                    "Waiting for confirmation of %s failed".formatted(correlationId), e.getCause());
        }

        if (confirm == null || !confirm.isAck()) {
            throw new EventPublishException(
                    "The broker refused %s: %s".formatted(
                            correlationId, confirm == null ? "no confirmation" : confirm.getReason()));
        }

        // Spring sets this before completing the confirm, so by here it is known either way.
        ReturnedMessage returned = correlation.getReturned();
        if (returned != null) {
            throw new EventPublishException(
                    "%s was not routed by '%s' with key '%s': %d %s".formatted(
                            correlationId, exchange, routingKey,
                            returned.getReplyCode(), returned.getReplyText()));
        }
    }
}
