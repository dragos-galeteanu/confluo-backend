package com.confluo.payments;

import com.confluo.amqp.EnableAmqp;
import com.confluo.common.exception.GlobalExceptionHandler;
import com.confluo.stripe.EnableStripe;
import com.confluo.webhook.EnableStripeWebhooks;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.data.mongodb.config.EnableMongoAuditing;

/**
 * Money: what was charged, to whom it was paid out, and what anybody is subscribed to.
 *
 * <p>This service holds no card details and never will. What it holds is Stripe's own identifiers
 * and a mirror of the objects they name, kept current by webhooks - which is what lets every other
 * service ask "has this been paid for?" without a network call to Stripe on the path of a request.
 * Stripe remains the source of truth for all of it; these tables are a read model that happens to
 * be durable.</p>
 *
 * <p>Four stores, as in {@code NotificationsApplication}, each holding a different kind of state.
 * PostgreSQL holds the ledger and the mirror, because a webhook that has been applied must never be
 * applied again and that is a unique constraint's job rather than a handler's. MongoDB holds the
 * raw body of every event Stripe sent, because that is the only record of what Stripe actually
 * said and its shape is theirs to change. Redis holds the in-flight locks and the token bucket in
 * front of Stripe's rate limits, which are the one thing here that may be lost on a restart.</p>
 *
 * <p>{@code @EnableAmqp} turns on both halves, and both are in use. This service publishes what it
 * learns - a payment taken, a subscription lapsed, an account whose payouts Stripe has stopped -
 * because those are facts other services act on and none of them should be polling for. It consumes
 * an order being cancelled, which is what sends the money back; see {@code OnOrderCancelled}, which
 * sits with the refunds it causes rather than in a package of its own. That is also the whole of how
 * another service asks this one for anything, since interservice communication on this platform is
 * AMQP and these HTTP routes are for people.</p>
 *
 * <h2>Nine subjects, and the three dependencies that run both ways</h2>
 *
 * <p>Each package under here is a subject rather than a layer: {@code marketplace},
 * {@code billing.subscription}, {@code connect} and the rest each hold their own entities,
 * repositories, services, routes, policy, handlers and request records. Three dependencies between
 * them run in both directions, and each is a question one subject can only answer by reading
 * another's tables:</p>
 *
 * <ul>
 *   <li>{@code marketplace} and {@code billing}, because {@code PlatformFeeCalculator} charges a
 *       seller less when their subscription tier says so, while {@code DiscountEligibility} refuses
 *       a first-purchase discount to somebody who has bought before.</li>
 *   <li>{@code billing.subscription} and {@code billing.discount}, because a discount applies to a
 *       subscription and a subscription's price is what a discount is a fraction of.</li>
 *   <li>{@code marketplace} and {@code payouts.dispute}, because a chargeback is an attribute of
 *       the sale it disputes and the clawback reads the sale to find the transfer.</li>
 * </ul>
 *
 * <p>They are why this is one service. Across a process boundary each becomes a synchronous call on
 * a path where a person is waiting, which AMQP-only interservice communication does not offer, and
 * {@code customer} is the hardest of the lot: one person must map to exactly one Stripe customer or
 * a saved card works for their subscription and not for their next purchase. Splitting anything off
 * starts by removing one of these three, not by drawing a line somewhere else.</p>
 *
 * <h2>The three opt-ins, and what each of them needs</h2>
 *
 * <p>{@code @EnableStripe} is the client and the six seams; {@code @EnableStripeWebhooks} is the
 * two endpoints, the event ledger and the sweep. Each is a module of its own because neither holds
 * any of this service's domain, and both are opt-in for the reason {@code common-core} is: a jar on
 * the classpath should not open an authenticated client or start applying events.</p>
 *
 * <p>{@code @EntityScan} is <strong>not</strong> redundant, which is the one thing to know before
 * deleting it. Any {@code @EntityScan} in the context replaces Spring Boot's default of the
 * application's own package, and payments-webhook declares one for its ledger row and its archived
 * body - so without this line naming this service's own package, every entity here would vanish and
 * every repository would fail at startup. {@code EnableStripeWebhooks} says the same thing from the
 * other side.</p>
 *
 * <p>Which Spring Data module owns which repository is still said explicitly, as in the other two
 * services, because with three of them on the classpath an unsaid answer is a puzzling startup
 * failure rather than a wrong setting. This service enables JPA repositories for its own package -
 * one scan for the lot, since a repository lives beside the feature it serves - and
 * payments-webhook enables both stores for the two it owns. {@code @EnableMongoAuditing} is what
 * fills a document's timestamps, including that module's; without it they stay null.</p>
 *
 * <p>{@link GlobalExceptionHandler} is imported by name because it lives in {@code common-core},
 * which this service's component scan does not reach. Leaving it out is not a startup failure but
 * a quiet one: every handled exception becomes a 500 instead of the status the handler would have
 * given it.</p>
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@Import(GlobalExceptionHandler.class)
@EnableAmqp
@EnableStripe
@EnableStripeWebhooks
@EnableJpaRepositories(basePackages = "com.confluo.payments")
@EntityScan("com.confluo.payments")
@EnableMongoAuditing
public class PaymentsApplication {

    /**
     * Starts the service.
     * @param args the command line arguments
     */
    public static void main(String[] args) {
        SpringApplication.run(PaymentsApplication.class, args);
    }
}
