package com.confluo.amqp.config;

import com.confluo.amqp.EnableAmqp;
import com.confluo.amqp.event.EventEnvelopeMessageConverter;
import com.confluo.amqp.event.EventTypeRegistry;
import com.confluo.amqp.metrics.AmqpMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.amqp.RabbitTemplateCustomizer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * What {@link EnableAmqp} brings into a service.
 *
 * <p>So far: the configuration properties, the catalogue of event contracts, and the converter
 * that turns an envelope into a message and back. None of it opens a connection or declares
 * anything on the broker, so a service can enable messaging and start normally before any of the
 * infrastructure exists.</p>
 *
 * <p>The beans that do reach the broker are added in their own phases - the template and topology,
 * the outbox drainer, the listener containers - each conditional on the half of the module a
 * service actually uses.</p>
 *
 * <p>{@code proxyBeanMethods = false}: the bean methods here take their collaborators as
 * parameters rather than calling one another, so there is no reason to pay for the CGLIB
 * subclass.</p>
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AmqpProperties.class)
@Slf4j
public class AmqpConfiguration {

    /**
     * The event contracts this service knows.
     *
     * <p>Scanned once, here, so a malformed or duplicated contract stops the service at startup
     * instead of at the first publish. The classpath is scanned rather than a list being
     * maintained, because a list is a second place to forget to update.</p>
     *
     * <p>Conditional on nothing else having provided one, so a service whose contracts live
     * somewhere other than the shared package can scan there instead.</p>
     *
     * @return the registry
     */
    @Bean
    @ConditionalOnMissingBean(EventTypeRegistry.class)
    EventTypeRegistry eventTypeRegistry() {
        return EventTypeRegistry.scanning(EventTypeRegistry.CONTRACTS_PACKAGE);
    }

    /**
     * The envelope converter, which is also the service's AMQP message converter.
     *
     * <p>Declared as a {@code MessageConverter}, so Spring Boot wires it into the
     * {@code RabbitTemplate} it builds and the listener containers pick it up: a service does not
     * get to accidentally publish an event in some other format.</p>
     *
     * @param registry the contracts the converter resolves payload classes through
     * @return the converter
     */
    @Bean
    EventEnvelopeMessageConverter eventEnvelopeMessageConverter(EventTypeRegistry registry) {
        return new EventEnvelopeMessageConverter(registry);
    }

    /**
     * The messaging metrics, on whichever registry the service has.
     *
     * <p>An {@code ObjectProvider} with a throwaway fallback rather than a condition on the
     * registry's presence: every real service has one through the actuator, and a module that
     * refused to start without it would be unusable in a plain test context. Falling back keeps
     * the instrumented code free of null checks.</p>
     *
     * @param registry the service's meter registry, if it has one
     * @return the metrics
     */
    @Bean
    AmqpMetrics amqpMetrics(ObjectProvider<MeterRegistry> registry) {
        return new AmqpMetrics(registry.getIfAvailable(SimpleMeterRegistry::new));
    }

    /**
     * Makes the template this service publishes through refuse to lose a message quietly.
     *
     * <p>{@code mandatory} is the important one. Without it, a message published to a routing key
     * nothing is bound to is discarded by the broker and the publish still looks like a success -
     * which, for an event nobody is listening to yet, is indistinguishable from working. With it,
     * the broker hands the message back and the returns callback says so.</p>
     *
     * <p>The callback only logs for now. The outbox drainer takes it over, because a returned
     * message means the entry it came from has not really been published and must not be marked as
     * though it had.</p>
     *
     * <p>This customises the template Spring Boot builds. A service that declares a
     * {@code RabbitTemplate} of its own gets none of this.</p>
     *
     * @param converter the envelope converter, so nothing can be published in another format
     * @return the customizer
     */
    @Bean
    RabbitTemplateCustomizer eventRabbitTemplateCustomizer(EventEnvelopeMessageConverter converter) {
        return template -> {
            template.setMessageConverter(converter);
            template.setMandatory(true);
            // Puts the trace context in the message headers, which is what lets one trace span the
            // HTTP request, the outbox row and whichever service ends up consuming the event.
            template.setObservationEnabled(true);
            template.setReturnsCallback(returned -> log.error(
                    "Event {} came back unrouted from exchange '{}' with key '{}': {} {}",
                    returned.getMessage().getMessageProperties()
                            .getHeader(EventEnvelopeMessageConverter.EVENT_ID_HEADER),
                    returned.getExchange(),
                    returned.getRoutingKey(),
                    returned.getReplyCode(),
                    returned.getReplyText()));
        };
    }
}
