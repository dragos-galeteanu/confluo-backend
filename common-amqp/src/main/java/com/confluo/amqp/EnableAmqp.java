package com.confluo.amqp;

import com.confluo.amqp.config.AmqpConfiguration;
import com.confluo.amqp.config.ConsumerConfiguration;
import com.confluo.amqp.config.OutboxConfiguration;
import com.confluo.amqp.config.TopologyConfiguration;
import org.springframework.context.annotation.Import;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Turns on asynchronous messaging for the service that declares it.
 *
 * <p>Messaging is opt-in rather than automatic, which is a deliberate choice and the same one
 * {@code common-core} makes: a service picks up {@code GlobalExceptionHandler} by importing it
 * from {@code PlatformApplication}, not by having the jar on its classpath. Auto-configuration
 * would mean that adding this module to a service's dependencies - to read an event record, say -
 * silently opened a broker connection and started declaring exchanges. Nothing happens here until
 * a service asks for it.</p>
 *
 * <p>Declared alongside the service's other opt-ins:</p>
 *
 * <pre>{@code
 * @SpringBootApplication
 * @Import(GlobalExceptionHandler.class)
 * @EnableAmqp
 * public class PlatformApplication { }
 * }</pre>
 *
 * <p>This imports {@link AmqpConfiguration} - the properties, the event contracts, the
 * converter and the publishing template - {@link TopologyConfiguration}, which names
 * everything and declares the one exchange this service owns, and
 * {@link OutboxConfiguration}, which is the publishing half and can be switched off with
 * {@code confluo.amqp.outbox.enabled=false} by a service that only consumes, and
 * {@link ConsumerConfiguration}, which is the consuming half and does nothing at all in a
 * service that declares no handlers. Nothing opens a connection while the context is building, so a
 * service still starts with the broker down.</p>
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Import({
        AmqpConfiguration.class,
        TopologyConfiguration.class,
        OutboxConfiguration.class,
        ConsumerConfiguration.class})
public @interface EnableAmqp {
}
