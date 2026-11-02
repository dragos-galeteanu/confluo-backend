package com.confluo.amqp.config;

import com.confluo.amqp.EnableAmqp;
import com.confluo.amqp.consume.ConsumedMessagePurger;
import com.confluo.amqp.consume.ConsumedMessageStore;
import com.confluo.amqp.consume.EventHandler;
import com.confluo.amqp.consume.EventHandlerRegistrar;
import com.confluo.amqp.consume.JdbcConsumedMessageStore;
import com.confluo.amqp.consume.Subscription;
import com.confluo.amqp.consume.Subscriptions;
import com.confluo.amqp.event.EventEnvelopeMessageConverter;
import com.confluo.amqp.event.EventTypeRegistry;
import com.confluo.amqp.metrics.AmqpMetrics;
import com.confluo.amqp.publish.ConfirmedPublisher;
import com.confluo.amqp.retry.RetryRepublishingErrorHandler;
import com.confluo.amqp.retry.RetryTopology;
import com.confluo.amqp.topology.AmqpNames;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Declarable;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;

/**
 * The consuming half of the module: the ledger, the queues and the listener containers.
 *
 * <p>Imported by {@link EnableAmqp} and switched off by
 * {@code confluo.amqp.consumer.enabled=false}. A service with no handlers needs no switch - it
 * declares no queues and starts no containers either way - so the property is there for the one
 * case the absence of handlers cannot express: a service that wants neither half and therefore has
 * no database for the ledger to live in.</p>
 *
 * <p>The topology is a bean rather than something declared imperatively, so {@code RabbitAdmin}
 * creates it when a connection is first opened. That keeps the two properties that matter: a
 * service still starts with the broker down, and a broker that comes back up - or comes up
 * empty - is repopulated without anyone running anything.</p>
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "confluo.amqp.consumer", name = "enabled", matchIfMissing = true)
@EnableScheduling
public class ConsumerConfiguration {

    /**
     * Every handler, resolved once.
     *
     * <p>An {@code ObjectProvider} rather than a {@code List}, because a service that publishes
     * and does not consume has no handlers at all and injecting an empty list would fail.</p>
     *
     * @param handlers the handler beans
     * @param registry the contracts
     * @param names this service's naming scheme
     * @return the subscriptions, in no particular order
     */
    @Bean
    Subscriptions subscriptions(
            ObjectProvider<EventHandler<?>> handlers,
            EventTypeRegistry registry,
            AmqpNames names) {
        return new Subscriptions(handlers.orderedStream()
                .map(handler -> Subscription.of(handler, registry, names))
                .toList());
    }

    /**
     * Every queue, exchange and binding the handlers need, retries and parking lots included.
     *
     * <p>Merged into one {@code Declarables} because several handlers share this service's retry,
     * requeue and parking-lot exchanges, and declaring the same object twice is wasteful rather
     * than wrong.</p>
     *
     * @param subscriptions the resolved handlers
     * @param topology what works out the retry shape
     * @return the declarables for {@code RabbitAdmin} to create
     */
    @Bean
    Declarables consumerTopology(Subscriptions subscriptions, RetryTopology topology) {
        List<Declarable> declarables = new ArrayList<>();
        for (Subscription subscription : subscriptions.all()) {
            declarables.addAll(topology.declarablesFor(
                            subscription.handler().producer(),
                            subscription.routingPattern(),
                            subscription.queue())
                    .getDeclarables());
        }
        return new Declarables(declarables);
    }

    /**
     * The consumed-message ledger.
     *
     * @param jdbc a template on the DataSource the handlers write to
     * @return the store
     */
    @Bean
    @ConditionalOnMissingBean(ConsumedMessageStore.class)
    ConsumedMessageStore consumedMessageStore(NamedParameterJdbcTemplate jdbc) {
        return new JdbcConsumedMessageStore(jdbc);
    }

    /**
     * Where a failed or unreadable message goes.
     *
     * <p>Its publisher is built here rather than injected, because the outbox half builds one too
     * and two beans of one type would be ambiguous to inject. It holds nothing, so a second
     * instance costs nothing.</p>
     *
     * @param topology which tier an attempt belongs in
     * @param names the retry and parking-lot exchanges
     * @param template the template Boot built, with confirms and returns configured
     * @param properties the attempt cap and the confirm timeout
     * @param metrics where the retried and parked counts go
     * @return the error handler
     */
    @Bean
    RetryRepublishingErrorHandler retryRepublishingErrorHandler(
            RetryTopology topology,
            AmqpNames names,
            RabbitTemplate template,
            AmqpProperties properties,
            AmqpMetrics metrics) {
        return new RetryRepublishingErrorHandler(topology, names,
                new ConfirmedPublisher(template, properties.confirmTimeout()), properties, metrics);
    }

    /**
     * What starts and stops the consumers.
     *
     * @param subscriptions the resolved handlers
     * @param connectionFactory the broker connection
     * @param admin what the containers redeclare through
     * @param converter what decodes an envelope
     * @param store the ledger
     * @param applicationContext where a container finds the observation registry
     * @param transactionManager the manager the claim and the handler share a transaction from
     * @param errorHandler where a failed or unreadable message goes
     * @param properties the prefetch and concurrency settings
     * @return the registrar
     */
    @Bean
    EventHandlerRegistrar eventHandlerRegistrar(
            Subscriptions subscriptions,
            ConnectionFactory connectionFactory,
            AmqpAdmin admin,
            EventEnvelopeMessageConverter converter,
            ConsumedMessageStore store,
            ApplicationContext applicationContext,
            PlatformTransactionManager transactionManager,
            RetryRepublishingErrorHandler errorHandler,
            AmqpProperties properties) {
        return new EventHandlerRegistrar(
                subscriptions.all(), connectionFactory, admin, converter, store, applicationContext,
                new TransactionTemplate(transactionManager), errorHandler, properties);
    }

    /**
     * What keeps the ledger bounded.
     *
     * @param store the ledger
     * @param properties the retention window
     * @return the purger
     */
    @Bean
    ConsumedMessagePurger consumedMessagePurger(
            ConsumedMessageStore store, AmqpProperties properties) {
        return new ConsumedMessagePurger(store, properties);
    }
}
