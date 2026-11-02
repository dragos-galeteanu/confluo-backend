package com.confluo.amqp.event;

import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.util.ClassUtils;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Every event contract this service knows, looked up either way round.
 *
 * <p>A publisher has a record and needs its name; a consumer has a name off the wire and needs the
 * record to decode into. Both go through here, so the two directions cannot disagree.</p>
 *
 * <p>The key is a type <em>and</em> a version, not a type alone. A breaking change bumps the
 * version and is published alongside the old event on the same routing key until consumers have
 * moved, so two records legitimately share a type and are told apart only by version.</p>
 *
 * <p>Built once at startup. A duplicate key or a malformed name is a startup failure rather than a
 * surprise at the first publish, because both are mistakes in code that no amount of retrying
 * fixes.</p>
 */
public class EventTypeRegistry {

    /** Where the event records live. Subpackages are included, one per producing domain. */
    public static final String CONTRACTS_PACKAGE = "com.confluo.amqp.event";

    /** Characters that would break a two-segment routing key or a binding pattern. */
    private static final List<String> FORBIDDEN_IN_NAMES = List.of(".", "*", "#", " ");

    private final Map<Key, EventDescriptor> byKey;
    private final Map<Class<?>, EventDescriptor> byPayloadType;

    private EventTypeRegistry(Collection<Class<?>> payloadTypes) {
        Map<Key, EventDescriptor> keys = new LinkedHashMap<>();
        Map<Class<?>, EventDescriptor> types = new LinkedHashMap<>();

        for (Class<?> payloadType : payloadTypes) {
            EventDescriptor descriptor = read(payloadType);
            EventDescriptor clash = keys.put(descriptor.key(), descriptor);
            if (clash != null) {
                throw new IllegalStateException(
                        "%s and %s both claim event %s; a type and version identify one contract"
                                .formatted(clash.payloadType().getName(), payloadType.getName(),
                                        descriptor.label()));
            }
            types.put(payloadType, descriptor);
        }

        this.byKey = Map.copyOf(keys);
        this.byPayloadType = Map.copyOf(types);
    }

    /**
     * Builds a registry from the records found in the given packages.
     * @param basePackages the packages to scan, subpackages included
     * @return the registry
     */
    public static EventTypeRegistry scanning(String... basePackages) {
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(DomainEvent.class));

        List<Class<?>> found = new java.util.ArrayList<>();
        for (String basePackage : basePackages) {
            scanner.findCandidateComponents(basePackage).forEach(definition -> {
                String className = definition.getBeanClassName();
                found.add(ClassUtils.resolveClassName(className, EventTypeRegistry.class.getClassLoader()));
            });
        }
        return new EventTypeRegistry(found);
    }

    /**
     * Builds a registry from an explicit list, which is how a test states exactly what exists.
     * @param payloadTypes the event records
     * @return the registry
     */
    public static EventTypeRegistry of(Class<?>... payloadTypes) {
        return new EventTypeRegistry(List.of(payloadTypes));
    }

    private static EventDescriptor read(Class<?> payloadType) {
        DomainEvent annotation = payloadType.getAnnotation(DomainEvent.class);
        if (annotation == null) {
            throw new IllegalArgumentException(
                    "%s is not an event: annotate it with @DomainEvent".formatted(payloadType.getName()));
        }
        String aggregate = requireName(payloadType, "aggregate", annotation.aggregate());
        String event = requireName(payloadType, "event", annotation.event());
        if (annotation.version() < 1) {
            throw new IllegalArgumentException(
                    "%s has version %d; versions start at 1"
                            .formatted(payloadType.getName(), annotation.version()));
        }
        return new EventDescriptor(payloadType, aggregate, event, annotation.version());
    }

    private static String requireName(Class<?> payloadType, String attribute, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                    "%s has a blank @DomainEvent %s".formatted(payloadType.getName(), attribute));
        }
        for (String forbidden : FORBIDDEN_IN_NAMES) {
            if (value.contains(forbidden)) {
                throw new IllegalArgumentException(
                        "%s has @DomainEvent %s '%s', which contains '%s'; a routing key has exactly two segments"
                                .formatted(payloadType.getName(), attribute, value, forbidden));
            }
        }
        return value;
    }

    /**
     * The contract for a record a service is about to publish.
     * @param payloadType the event record
     * @return its descriptor
     * @throws IllegalArgumentException if the record is not a registered event
     */
    public EventDescriptor describe(Class<?> payloadType) {
        EventDescriptor descriptor = byPayloadType.get(payloadType);
        if (descriptor == null) {
            throw new IllegalArgumentException(
                    "%s is not a registered event; is it annotated with @DomainEvent and in %s?"
                            .formatted(payloadType.getName(), CONTRACTS_PACKAGE));
        }
        return descriptor;
    }

    /**
     * The contract a message off the wire refers to.
     *
     * <p>Empty rather than an exception, because an unknown type is not necessarily a fault: a
     * producer may have started publishing a version this service has not been taught yet. What to
     * do about it belongs to the consumer, which parks what it cannot decode.</p>
     *
     * @param eventType the wire name
     * @param version the contract version
     * @return the descriptor, or empty if this service does not know that contract
     */
    public Optional<EventDescriptor> resolve(String eventType, int version) {
        if (eventType == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(byKey.get(new Key(eventType.toLowerCase(Locale.ROOT), version)));
    }

    /**
     * Every contract this service knows, in the order they were found.
     * @return the descriptors
     */
    public Collection<EventDescriptor> descriptors() {
        return byPayloadType.values();
    }

    /**
     * What identifies one contract: the wire name together with the version.
     *
     * <p>The type is held lower-cased so a difference in case cannot produce two entries for one
     * contract.</p>
     *
     * @param eventType the lower-cased wire name
     * @param version the contract version
     */
    public record Key(String eventType, int version) {
    }
}
