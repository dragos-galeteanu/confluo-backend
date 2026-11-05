package com.confluo.amqp.config;

import com.confluo.amqp.EnableAmqp;
import com.confluo.amqp.retry.RetryTopology;
import com.confluo.amqp.topology.AmqpNames;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * The naming scheme, the retry shape, and the one exchange this service owns.
 *
 * <p>Imported by {@link EnableAmqp}. Declaring a {@code Declarables} bean is all that is
 * needed for the objects in it to exist: Spring's {@code RabbitAdmin} declares them when a
 * connection is first opened, idempotently, so a fresh broker heals itself and local development
 * needs no setup step. Nothing here opens that connection, which is why a service can still start
 * with no broker running.</p>
 *
 * <p>A consumer's queues are not here. They depend on which handlers a service declares, so they
 * are built from {@link RetryTopology} as the handlers are discovered.</p>
 */
@Configuration(proxyBeanMethods = false)
public class TopologyConfiguration {

    /**
     * The names every AMQP object in this service is derived from.
     *
     * @param properties the messaging settings
     * @param environment where {@code spring.application.name} is read from, as the fallback
     * @return the naming scheme
     */
    @Bean
    AmqpNames amqpNames(AmqpProperties properties, Environment environment) {
        return new AmqpNames(
                properties.resolveServiceName(environment.getProperty("spring.application.name")));
    }

    /**
     * The retry and parking-lot shape a consumer's queues are built from.
     *
     * <p>Constructed here even in a service that consumes nothing, because its constructor is
     * where an inconsistent {@code max-attempts} and {@code retry-delays} pair is caught. Better a
     * startup failure in any service than a topology that only turns out to be wrong the first
     * time something fails.</p>
     *
     * @param names the naming scheme
     * @param properties the messaging settings
     * @return the retry topology
     */
    @Bean
    RetryTopology retryTopology(AmqpNames names, AmqpProperties properties) {
        return new RetryTopology(names, properties);
    }

    /**
     * The topic exchange this service publishes its own events to.
     *
     * <p>Only declared when the service publishes. {@code confluo.amqp.outbox.enabled} is that
     * switch: every publish in this module goes through the outbox, so a service with nothing to
     * drain is a service that does not publish, and it has no business owning an events
     * exchange.</p>
     *
     * @param names the naming scheme
     * @return the exchange, for {@code RabbitAdmin} to declare
     */
    @Bean
    @ConditionalOnProperty(prefix = "confluo.amqp.outbox", name = "enabled", matchIfMissing = true)
    Declarables eventsExchange(AmqpNames names) {
        return new Declarables(new TopicExchange(names.eventsExchange(), true, false));
    }
}
