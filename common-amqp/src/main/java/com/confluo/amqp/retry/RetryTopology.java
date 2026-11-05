package com.confluo.amqp.retry;

import com.confluo.amqp.config.AmqpProperties.QueueType;
import com.confluo.amqp.config.AmqpProperties;
import com.confluo.amqp.topology.AmqpNames;
import com.confluo.amqp.topology.QueueArguments;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarable;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The exchanges, queues and bindings one subscription needs, retries included.
 *
 * <p>The shape is worth following, because the obvious version of it does not work. A failing
 * handler cannot simply nack: a nacked message is dead-lettered with the routing key it arrived
 * with, so nacking cannot choose between a five-second and a five-minute wait. Instead the message
 * is republished to {@code <service>.retry} with the tier queue's own name as the routing key, and
 * acked. The tier queue holds it - no consumer, a queue-level TTL - and on expiry dead-letters it
 * to {@code <service>.requeue} with the main queue's name as the key, where a second binding on
 * the main queue brings it back. The wait happens in the broker, so no consumer thread and no
 * prefetch slot is held, and a redeploy in the middle of a backoff loses nothing.</p>
 *
 * <p>A returning message no longer carries the routing key it was published with, because
 * dead-lettering replaced it. Nothing depends on that: what the message is remains in the
 * envelope.</p>
 *
 * <p>The main queue also dead-letters, to the parking lot. The error handler always republishes and
 * acks, so nothing should ever be rejected - but {@code default-requeue-rejected} is false, which
 * means anything rejected outside the handler would be dropped rather than kept. This way it is
 * kept.</p>
 */
public class RetryTopology {

    private final AmqpNames names;
    private final AmqpProperties properties;

    /**
     * @param names the service's naming scheme
     * @param properties the consumer settings, which supply the tiers and the attempt cap
     */
    public RetryTopology(AmqpNames names, AmqpProperties properties) {
        this.names = names;
        this.properties = properties;

        int attempts = properties.consumer().maxAttempts();
        int tiers = properties.consumer().retryDelays().size();
        if (tiers != attempts - 1) {
            throw new IllegalStateException(
                    ("confluo.amqp.consumer has %d retry-delays and max-attempts %d: there is one "
                            + "delay per retry, so the last attempt needs no delay and there should be %d")
                            .formatted(tiers, attempts, attempts - 1));
        }
    }

    /**
     * Everything the broker needs for one handler to receive, retry and eventually park an event.
     *
     * <p>The producer's exchange is declared here too, not only the consumer's own objects. A
     * consumer may be deployed before the service it listens to exists, and binding to an exchange
     * that is not there fails - declaring it is idempotent and costs nothing.</p>
     *
     * @param producer the publishing service's name
     * @param routingPattern what to bind with, an exact type or a pattern like {@code currency.*}
     * @param queue the main queue's name, from {@link AmqpNames#queue(String, String)}
     * @return the declarables, in the order they are declared
     */
    public Declarables declarablesFor(String producer, String routingPattern, String queue) {
        QueueType queueType = properties.queueType();
        TopicExchange events = new TopicExchange(names.eventsExchangeOf(producer), true, false);
        DirectExchange retry = new DirectExchange(names.retryExchange(), true, false);
        DirectExchange requeue = new DirectExchange(names.requeueExchange(), true, false);
        DirectExchange parkingLot = new DirectExchange(names.parkingLotExchange(), true, false);

        List<Declarable> declarables = new ArrayList<>(List.of(events, retry, requeue, parkingLot));

        Queue main = durableQueue(queue, QueueArguments.of(queueType)
                .deadLetteringTo(parkingLot.getName(), queue)
                .build());
        declarables.add(main);
        declarables.add(BindingBuilder.bind(main).to(events).with(routingPattern));
        declarables.add(BindingBuilder.bind(main).to(requeue).with(queue));

        for (Duration delay : properties.consumer().retryDelays()) {
            Queue tier = durableQueue(names.retryQueue(queue, delay), QueueArguments.of(queueType)
                    .deadLetteringTo(requeue.getName(), queue)
                    .expiringAfter(delay)
                    .build());
            declarables.add(tier);
            declarables.add(BindingBuilder.bind(tier).to(retry).with(tier.getName()));
        }

        Queue parked = durableQueue(names.parkingLotQueue(queue), QueueArguments.of(queueType).build());
        declarables.add(parked);
        declarables.add(BindingBuilder.bind(parked).to(parkingLot).with(queue));

        return new Declarables(declarables);
    }

    /**
     * Where a message goes after failing for the nth time.
     *
     * <p>Empty means there is no tier left and the message belongs in the parking lot, which is
     * what the attempt cap means in practice.</p>
     *
     * @param queue the main queue's name
     * @param attempt how many times handling has failed, counting from 1
     * @return the tier queue's name, which is also the routing key to republish with
     */
    public Optional<String> retryQueueFor(String queue, int attempt) {
        List<Duration> delays = properties.consumer().retryDelays();
        if (attempt < 1 || attempt > delays.size()) {
            return Optional.empty();
        }
        return Optional.of(names.retryQueue(queue, delays.get(attempt - 1)));
    }

    /**
     * The key a tier queue dead-letters with to reach the main queue again.
     * @param queue the main queue's name
     * @return the routing key, which is the queue's own name
     */
    public String requeueRoutingKey(String queue) {
        return queue;
    }

    /**
     * The key a message that has run out of attempts is republished with.
     * @param queue the main queue's name
     * @return the routing key, which is the queue's own name
     */
    public String parkingLotRoutingKey(String queue) {
        return queue;
    }

    private static Queue durableQueue(String name, java.util.Map<String, Object> arguments) {
        return new Queue(name, true, false, false, arguments);
    }
}
