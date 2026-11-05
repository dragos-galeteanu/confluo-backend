package com.confluo.amqp.consume;

import com.confluo.amqp.event.EventDescriptor;
import com.confluo.amqp.event.EventTypeRegistry;
import com.confluo.amqp.topology.AmqpNames;
import org.springframework.aop.support.AopUtils;
import org.springframework.core.ResolvableType;

/**
 * One handler, resolved: what it listens to, where that arrives, and what it is called.
 *
 * <p>Worked out once and shared, because the topology and the listener containers both need all of
 * it and must agree - a queue declared under one name and consumed under another is a service that
 * starts cleanly and receives nothing.</p>
 *
 * @param handler the handler itself
 * @param descriptor the contract it handles
 * @param name its name in the consumed-message ledger
 * @param queue the queue it consumes
 * @param routingPattern what that queue is bound with
 */
public record Subscription(
        EventHandler<?> handler,
        EventDescriptor descriptor,
        String name,
        String queue,
        String routingPattern) {

    /**
     * Works out a handler's subscription.
     *
     * @param handler the handler bean
     * @param registry the contracts, to describe the handled event
     * @param names this service's naming scheme
     * @return the subscription
     * @throws IllegalArgumentException if the handler's type parameter cannot be resolved or is not
     *                                  a registered event
     */
    public static Subscription of(EventHandler<?> handler, EventTypeRegistry registry, AmqpNames names) {
        Class<?> handlerClass = AopUtils.getTargetClass(handler);
        Class<?> payloadType = ResolvableType.forClass(handlerClass)
                .as(EventHandler.class)
                .resolveGeneric(0);
        if (payloadType == null) {
            throw new IllegalArgumentException(
                    ("%s does not say which event it handles: implement EventHandler<SomeEvent> "
                            + "rather than the raw interface")
                            .formatted(handlerClass.getName()));
        }

        EventDescriptor descriptor = registry.describe(payloadType);
        String pattern = handler.routingPattern() != null
                ? handler.routingPattern()
                : descriptor.type();
        String suffix = handler.queueSuffix() != null
                ? handler.queueSuffix()
                : descriptor.type();
        String name = handler.name() != null ? handler.name() : handlerClass.getName();

        return new Subscription(
                handler,
                descriptor,
                name,
                names.queue(handler.producer(), suffix),
                pattern);
    }

    /**
     * The record this handler's events decode into.
     * @return the payload type
     */
    public Class<?> payloadType() {
        return descriptor.payloadType();
    }
}
