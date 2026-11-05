package com.confluo.amqp.config;

import com.confluo.amqp.EnableAmqp;
import com.confluo.amqp.event.EventEnvelopeMessageConverter;
import com.confluo.amqp.event.EventTypeRegistry;
import com.confluo.amqp.health.OutboxHealthIndicator;
import com.confluo.amqp.metrics.AmqpMetrics;
import com.confluo.amqp.outbox.JdbcOutboxRepository;
import com.confluo.amqp.outbox.OutboxDrainer;
import com.confluo.amqp.outbox.OutboxRepository;
import com.confluo.amqp.publish.ConfirmedPublisher;
import com.confluo.amqp.publish.EventPublisher;
import com.confluo.amqp.publish.OutboxEventPublisher;
import com.confluo.amqp.publish.RabbitEventSender;
import com.confluo.amqp.topology.AmqpNames;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * The publishing half of the module: the outbox, the sender and the drainer.
 *
 * <p>Imported by {@link EnableAmqp} and switched off by
 * {@code confluo.amqp.outbox.enabled=false}, which is what a consumer-only service sets. There is
 * deliberately no condition on a {@code DataSource} or a {@code RabbitTemplate} being present: a
 * service that says it publishes and has no database to publish from is misconfigured, and a
 * missing-bean failure at startup naming the type is a better outcome than messaging silently doing
 * nothing.</p>
 *
 * <p>{@code @EnableScheduling} is here because the sweep has to run on a timer, and a scheduled
 * method in a service with no scheduler is not an error - it simply never runs. The outbox would
 * then publish only on the nudge, which is to say it would lose exactly the events the sweep exists
 * to recover. Enabling it twice is harmless.</p>
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "confluo.amqp.outbox", name = "enabled", matchIfMissing = true)
@EnableScheduling
public class OutboxConfiguration {

    /**
     * The outbox, on the service's own database.
     *
     * <p>Conditional on nothing else having provided one, so a service on another store can supply
     * its own implementation without taking the rest of the module apart.</p>
     *
     * @param jdbc a template on the DataSource the business data is in
     * @return the repository
     */
    @Bean
    @ConditionalOnMissingBean(OutboxRepository.class)
    OutboxRepository outboxRepository(NamedParameterJdbcTemplate jdbc) {
        return new JdbcOutboxRepository(jdbc);
    }

    /**
     * What gets one entry to the broker and waits to be told it arrived.
     *
     * @param template the template Boot built, with confirms and returns configured
     * @param converter where the wire format is defined
     * @param names this service's names, for the source header
     * @param properties the confirm timeout
     * @return the sender
     */
    @Bean
    RabbitEventSender rabbitEventSender(
            RabbitTemplate template,
            EventEnvelopeMessageConverter converter,
            AmqpNames names,
            AmqpProperties properties) {
        return new RabbitEventSender(
                new ConfirmedPublisher(template, properties.confirmTimeout()),
                converter, names.serviceName());
    }

    /**
     * The only thing a service calls to publish.
     *
     * @param registry the contracts
     * @param converter what serializes the envelope
     * @param repository the outbox
     * @param names this service's names
     * @param applicationEvents how the drainer hears about a new entry
     * @return the publisher
     */
    @Bean
    EventPublisher eventPublisher(
            EventTypeRegistry registry,
            EventEnvelopeMessageConverter converter,
            OutboxRepository repository,
            AmqpNames names,
            ApplicationEventPublisher applicationEvents) {
        return new OutboxEventPublisher(registry, converter, repository, names, applicationEvents);
    }

    /**
     * The nudge and the sweep.
     *
     * @param repository the outbox
     * @param sender what publishes one entry
     * @param properties the batch size, attempt cap and poll interval
     * @param metrics where the published and failed counts go
     * @param transactionManager the manager the drainer opens its own transactions with
     * @return the drainer
     */
    @Bean
    OutboxDrainer outboxDrainer(
            OutboxRepository repository,
            RabbitEventSender sender,
            AmqpProperties properties,
            AmqpMetrics metrics,
            PlatformTransactionManager transactionManager) {
        metrics.watchPending(repository::countPending);
        return new OutboxDrainer(repository, sender, properties, metrics, transactionManager);
    }

    /**
     * Puts the sweep on a timer.
     *
     * <p>Registered here rather than with {@code @Scheduled} on the method, because that reads the
     * interval as a raw string and accepts only milliseconds or ISO-8601: {@code poll-interval: 1s}
     * - which is what anyone would write, and what this module's own default is - would stop the
     * service at startup. The bound {@link java.time.Duration} has already been through Spring
     * Boot's relaxed parsing, so every form it accepts works here.</p>
     *
     * @param drainer what to run
     * @param properties the interval to run it at
     * @return the scheduling configurer
     */
    @Bean
    SchedulingConfigurer outboxSweep(
            OutboxDrainer drainer, AmqpProperties properties) {
        return (ScheduledTaskRegistrar registrar) ->
                registrar.addFixedDelayTask(drainer::sweep, properties.outbox().pollInterval());
    }

    /**
     * Says whether events are actually leaving this service.
     *
     * <p>Named so the actuator exposes it as {@code outbox} under
     * {@code /actuator/health}.</p>
     *
     * @param repository the outbox
     * @param properties how old the oldest entry may get
     * @return the health indicator
     */
    @Bean
    OutboxHealthIndicator outboxHealthIndicator(
            OutboxRepository repository, AmqpProperties properties) {
        return new OutboxHealthIndicator(repository, properties);
    }
}
