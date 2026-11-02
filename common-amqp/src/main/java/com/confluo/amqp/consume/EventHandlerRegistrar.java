package com.confluo.amqp.consume;

import com.confluo.amqp.config.AmqpProperties;
import com.confluo.amqp.retry.RetryRepublishingErrorHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.ApplicationContext;
import org.springframework.context.SmartLifecycle;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;

/**
 * Gives every handler a running consumer.
 *
 * <p>One container per handler rather than one per service, so that a queue's concurrency, its
 * prefetch and its failures belong to the handler they concern: a slow handler cannot hold up an
 * unrelated one, and a handler that keeps failing fills its own parking lot.</p>
 *
 * <p>A {@link SmartLifecycle} rather than something that starts in a constructor, so the containers
 * start when the context does and stop when it stops - which gives in-flight messages a chance to
 * finish and be acknowledged instead of being redelivered after a restart.</p>
 */
@Slf4j
public class EventHandlerRegistrar implements SmartLifecycle {

    private final List<Subscription> subscriptions;
    private final ConnectionFactory connectionFactory;
    private final AmqpAdmin admin;
    private final MessageConverter converter;
    private final ConsumedMessageStore store;
    private final ApplicationContext applicationContext;
    private final TransactionTemplate transaction;
    private final RetryRepublishingErrorHandler errorHandler;
    private final AmqpProperties properties;

    private final List<SimpleMessageListenerContainer> containers = new ArrayList<>();

    private volatile boolean running;

    /**
     * @param subscriptions every handler, already resolved
     * @param connectionFactory the broker connection
     * @param admin what the containers redeclare their queues through
     * @param converter what decodes an envelope
     * @param store the consumed-message ledger
     * @param applicationContext where a container finds the observation registry
     * @param transaction the transaction a claim and its handler share
     * @param errorHandler where a failed or unreadable message goes
     * @param properties the prefetch and concurrency settings
     */
    public EventHandlerRegistrar(
            List<Subscription> subscriptions,
            ConnectionFactory connectionFactory,
            AmqpAdmin admin,
            MessageConverter converter,
            ConsumedMessageStore store,
            ApplicationContext applicationContext,
            TransactionTemplate transaction,
            RetryRepublishingErrorHandler errorHandler,
            AmqpProperties properties) {
        this.subscriptions = List.copyOf(subscriptions);
        this.connectionFactory = connectionFactory;
        this.admin = admin;
        this.converter = converter;
        this.store = store;
        this.applicationContext = applicationContext;
        this.transaction = transaction;
        this.errorHandler = errorHandler;
        this.properties = properties;
    }

    @Override
    public void start() {
        if (running) {
            return;
        }
        for (Subscription subscription : subscriptions) {
            SimpleMessageListenerContainer container = containerFor(subscription);
            containers.add(container);
            container.start();
            log.info("Consuming {} from '{}' with {}",
                    subscription.descriptor().label(), subscription.queue(), subscription.name());
        }
        running = true;
    }

    @Override
    public void stop() {
        containers.forEach(SimpleMessageListenerContainer::stop);
        containers.clear();
        running = false;
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    private SimpleMessageListenerContainer containerFor(Subscription subscription) {
        AmqpProperties.Consumer consumer = properties.consumer();

        SimpleMessageListenerContainer container = new SimpleMessageListenerContainer(connectionFactory);
        container.setBeanName("eventConsumer-" + subscription.queue());

        // These containers are built rather than declared as beans, so nothing has handed them the
        // context - and without it a container cannot find the observation registry, which is
        // where the consuming half of a trace comes from.
        container.setApplicationContext(applicationContext);
        container.setObservationEnabled(true);

        container.setQueueNames(subscription.queue());
        container.setAmqpAdmin(admin);
        container.setPrefetchCount(consumer.prefetch());
        container.setConcurrentConsumers(consumer.concurrency());
        container.setMaxConcurrentConsumers(consumer.maxConcurrency());

        // Acknowledgement is the listener's business: it has four outcomes to tell apart.
        container.setAcknowledgeMode(AcknowledgeMode.MANUAL);

        // Set here as well as in configuration, because the default is the dangerous one: a
        // requeued rejection is redelivered immediately, so a message that always fails would spin
        // a consumer at full speed.
        container.setDefaultRequeueRejected(false);

        container.setMessageListener(new EnvelopeMessageListener(
                subscription, converter, store, transaction, errorHandler));
        container.afterPropertiesSet();
        return container;
    }
}
