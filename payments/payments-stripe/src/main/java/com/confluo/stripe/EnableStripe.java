package com.confluo.stripe;

import org.springframework.context.annotation.Import;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Turns on Stripe for the service that declares it.
 *
 * <p>Opt-in rather than automatic, which is the choice {@code common-amqp} and {@code common-core}
 * both make. Having this jar on the classpath has to be free: a service that only wants a
 * gateway's record type, or the exception, should not thereby open an authenticated client against
 * an account whose key it has not configured.</p>
 *
 * <pre>{@code
 * @SpringBootApplication
 * @EnableStripe
 * public class PaymentsApplication { }
 * }</pre>
 *
 * <p>This imports {@link StripeConfig} - the credentials, the one {@code StripeClient} and the
 * assertion that stripe-java still pins the API version this platform was written against - and
 * {@link StripeGatewayConfiguration}, which is the six seams.</p>
 *
 * <p><strong>Nothing in this module imports a service's own class</strong>, which is the invariant
 * that makes it a module rather than a package: no entity, no repository, no DTO, and no web or
 * database dependency. A gateway takes Stripe's own parameter object and returns a small record of
 * what came back; the caller decides what the answer means, and even where this platform's pages
 * live is passed in as an argument. One import of a service's class would put this back inside that
 * service.</p>
 *
 * <p>It needs {@code confluo.stripe.secret-key}, {@code confluo.stripe.webhook-secret} and
 * {@code confluo.stripe.connect-webhook-secret}. All three are {@code @NotBlank}, so a missing one
 * fails startup rather than the first charge or the first webhook - see {@link StripeProperties}
 * for why that trade is worth making. The client is built eagerly and talks to nothing while the
 * context builds, so a service still starts with Stripe unreachable.</p>
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Import({StripeConfig.class, StripeGatewayConfiguration.class})
public @interface EnableStripe {
}
