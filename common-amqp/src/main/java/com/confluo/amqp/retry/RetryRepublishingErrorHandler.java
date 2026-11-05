package com.confluo.amqp.retry;

import com.confluo.amqp.config.AmqpProperties;
import com.confluo.amqp.metrics.AmqpMetrics;
import com.confluo.amqp.publish.ConfirmedPublisher;
import com.confluo.amqp.topology.AmqpNames;
import com.rabbitmq.client.Channel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;

import java.io.IOException;
import java.util.Optional;

/**
 * What happens to a message whose handler failed: another go later, or the parking lot.
 *
 * <p>It republishes and acknowledges rather than rejecting, and that is the whole reason this class
 * exists. A rejected message is dead-lettered with the routing key it arrived with, so rejecting
 * cannot choose between a five-second and a five-minute wait - every failure would go to the same
 * place. Republishing to {@code <service>.retry} with the tier queue's own name as the key picks the
 * wait, and the original is then acknowledged because a copy of it is safely elsewhere.</p>
 *
 * <p>The attempt count is a header this module owns, {@code x-confluo-attempt}, rather than the
 * {@code x-death} count the broker maintains. The broker counts dead-letterings, and a republish is
 * not one - so {@code x-death} would stay at zero however many times a message went round.</p>
 *
 * <p>Order matters: publish first, acknowledge second. The other way round, a republish that failed
 * after the acknowledgement would lose the message. If the republish does fail, the original is
 * rejected instead, which dead-letters it to the parking lot - so the worst case is a message
 * parked early rather than a message gone.</p>
 */
@Slf4j
public class RetryRepublishingErrorHandler {

    /** How many times handling has failed. Ours, because {@code x-death} does not count republishes. */
    public static final String ATTEMPT_HEADER = "x-confluo-attempt";

    /** Why a parked message was parked, for whoever finds it in the management UI. */
    public static final String PARKED_REASON_HEADER = "x-confluo-parked-reason";

    private final RetryTopology topology;
    private final AmqpNames names;
    private final ConfirmedPublisher publisher;
    private final AmqpProperties properties;
    private final AmqpMetrics metrics;

    /**
     * @param topology which tier an attempt belongs in
     * @param names the retry and parking-lot exchanges
     * @param publisher what republishes, and waits to be told it worked
     * @param properties the attempt cap
     * @param metrics where the retried and parked counts go
     */
    public RetryRepublishingErrorHandler(
            RetryTopology topology,
            AmqpNames names,
            ConfirmedPublisher publisher,
            AmqpProperties properties,
            AmqpMetrics metrics) {
        this.topology = topology;
        this.names = names;
        this.publisher = publisher;
        this.properties = properties;
        this.metrics = metrics;
    }

    /**
     * Sends a failed message to its next tier, or to the parking lot if it has run out.
     *
     * @param message the message as it arrived
     * @param channel the channel it arrived on
     * @param queue the queue it arrived from
     * @param description what the message is, for the log
     * @param failure why handling failed
     * @throws IOException if the channel cannot be acknowledged
     */
    public void onFailure(
            Message message, Channel channel, String queue, String description, Exception failure)
            throws IOException {
        int attempt = attemptsSoFar(message) + 1;
        int cap = properties.consumer().maxAttempts();

        Optional<String> tier = attempt < cap
                ? topology.retryQueueFor(queue, attempt)
                : Optional.empty();

        if (tier.isEmpty()) {
            park(message, channel, queue, description,
                    "failed %d time(s): %s".formatted(attempt, failure.getMessage()), attempt);
            return;
        }

        try {
            publisher.publish(names.retryExchange(), tier.get(),
                    copyWithAttempt(message, attempt, null), description);
            channel.basicAck(message.getMessageProperties().getDeliveryTag(), false);
            metrics.retried(queue, attempt);
            log.warn("Attempt {} of {} failed for {} on '{}'; waiting in '{}': {}",
                    attempt, cap, description, queue, tier.get(), failure.getMessage());
        } catch (RuntimeException e) {
            log.error("Could not send {} to '{}'; parking it instead: {}",
                    description, tier.get(), e.getMessage());
            reject(message, channel);
        }
    }

    /**
     * Sends a message to the parking lot, where nothing consumes it.
     *
     * <p>Also the route for a message that could not be decoded, which is not retried at all: a
     * message that cannot be read will not become readable.</p>
     *
     * @param message the message as it arrived
     * @param channel the channel it arrived on
     * @param queue the queue it arrived from
     * @param description what the message is, for the log
     * @param reason why it is being parked
     * @throws IOException if the channel cannot be acknowledged
     */
    public void park(Message message, Channel channel, String queue, String description, String reason)
            throws IOException {
        park(message, channel, queue, description, reason, attemptsSoFar(message));
    }

    private void park(
            Message message, Channel channel, String queue, String description, String reason,
            int attempt) throws IOException {
        try {
            publisher.publish(names.parkingLotExchange(), topology.parkingLotRoutingKey(queue),
                    copyWithAttempt(message, attempt, reason), description);
            channel.basicAck(message.getMessageProperties().getDeliveryTag(), false);
        } catch (RuntimeException e) {
            // The parking lot is also the main queue's dead-letter route, so rejecting gets it
            // there too - just without the reason header.
            log.error("Could not send {} to the parking lot; rejecting it instead: {}",
                    description, e.getMessage());
            reject(message, channel);
        }
        metrics.parked(queue);
        log.error("Parked {} from '{}' in '{}': {}",
                description, queue, names.parkingLotQueue(queue), reason);
    }

    private void reject(Message message, Channel channel) throws IOException {
        channel.basicReject(message.getMessageProperties().getDeliveryTag(), false);
    }

    /**
     * How many times this message has already failed.
     *
     * <p>Read loosely because a header crossing the wire may come back as any integral type, and a
     * message republished by hand may carry text.</p>
     *
     * @param message the message
     * @return the count, or zero if it has never failed
     */
    static int attemptsSoFar(Message message) {
        Object header = message.getMessageProperties().getHeader(ATTEMPT_HEADER);
        if (header instanceof Number number) {
            return number.intValue();
        }
        if (header != null) {
            try {
                return Integer.parseInt(header.toString().trim());
            } catch (NumberFormatException e) {
                log.warn("Ignoring an unreadable {} header: {}", ATTEMPT_HEADER, header);
            }
        }
        return 0;
    }

    /**
     * The same message, with the attempt count brought up to date.
     *
     * <p>A received message's {@code deliveryMode} is null - the one it arrived with is in
     * {@code receivedDeliveryMode} - so republishing it unchanged would quietly make a retried
     * message less durable than the original.</p>
     *
     * @param message the message as it arrived
     * @param attempt the count to record
     * @param reason the parking reason, or null when this is a retry
     * @return the message to publish
     */
    private static Message copyWithAttempt(Message message, int attempt, String reason) {
        MessageProperties source = message.getMessageProperties();
        MessageProperties properties = new MessageProperties();

        source.getHeaders().forEach(properties::setHeader);
        properties.setHeader(ATTEMPT_HEADER, attempt);
        if (reason != null) {
            properties.setHeader(PARKED_REASON_HEADER, reason);
        }
        properties.setContentType(source.getContentType());
        properties.setContentEncoding(source.getContentEncoding());
        properties.setMessageId(source.getMessageId());
        properties.setCorrelationId(source.getCorrelationId());
        properties.setTimestamp(source.getTimestamp());
        properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);

        return new Message(message.getBody(), properties);
    }
}
