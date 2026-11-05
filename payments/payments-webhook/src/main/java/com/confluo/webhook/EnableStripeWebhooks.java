package com.confluo.webhook;

import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.Import;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Turns on Stripe's webhooks for the service that declares it.
 *
 * <p>Two endpoints - the platform's own events and its connected accounts', which have different
 * signing secrets - a ledger that makes applying an event twice impossible, and a sweep that
 * applies them afterwards. Opt-in, like {@code @EnableAmqp} and {@link
 * com.confluo.stripe.EnableStripe}: the jar on the classpath has to be free, because a service may
 * want only {@link StripeEventHandler} to declare against.</p>
 *
 * <pre>{@code
 * @SpringBootApplication
 * @EnableStripe
 * @EnableStripeWebhooks
 * @EntityScan("com.confluo.payments")
 * public class PaymentsApplication { }
 * }</pre>
 *
 * <p>What a service has to do for itself, and what goes wrong if it does not:</p>
 *
 * <ul>
 *   <li><strong>Its own {@link EntityScan}.</strong> This module declares one for its two
 *       documents, and any {@code @EntityScan} anywhere replaces Spring Boot's default of the
 *       application's own package. Without one of its own a service loses every entity it has.
 *       Loudly, at startup - but only if it has JPA repositories, which is why this is written
 *       down here rather than left to be discovered.</li>
 *   <li><strong>Its Flyway locations.</strong> The ledger's table ships in this jar under
 *       {@code classpath:db/migration/webhook}, versioned 1100-1199 - after a service's own
 *       1-999 and common-amqp's 1000-1099, because Flyway requires versions to be unique across
 *       every location it is given. A service that forgets the location starts and then fails
 *       {@code ddl-auto: validate} on a table that does not exist.</li>
 *   <li><strong>{@code @EnableMongoAuditing},</strong> if it wants the archived bodies to carry
 *       timestamps. Not declared here, because declaring it twice in one context is a bean
 *       conflict and a service is the one that knows whether it has already done so.</li>
 *   <li><strong>{@link com.confluo.stripe.EnableStripe},</strong> for the signing secrets.</li>
 * </ul>
 *
 * <p>This imports {@link WebhookConfiguration} - the properties, the two stores and the endpoints -
 * and {@link WebhookSweepConfiguration}, which puts the sweep on a timer and can be switched off
 * with {@code confluo.webhook.enabled=false}. Nothing here talks to Stripe or to a broker while the
 * context builds.</p>
 *
 * <p>The dependency runs one way: a service implements {@link StripeEventHandler} to say what an
 * event type means for its own tables, the sweep collects the implementations by event type, and
 * nothing in this module imports any of them. So a new event type is a class in the service that
 * cares about it, never an edit here - which is what made this a module and not a package. What it
 * does own is two tables, which is why there is a migration to wire in.</p>
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Import({WebhookConfiguration.class, WebhookSweepConfiguration.class})
public @interface EnableStripeWebhooks {
}
