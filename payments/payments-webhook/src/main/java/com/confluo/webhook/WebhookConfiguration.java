package com.confluo.webhook;

import com.confluo.stripe.StripeProperties;
import com.confluo.webhook.payload.StripeEventPayload;
import com.confluo.webhook.payload.StripeEventPayloadRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.data.mongodb.repository.config.EnableMongoRepositories;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * What {@link EnableStripeWebhooks} brings into a service.
 *
 * <p>Every bean is declared rather than component-scanned, because a service's scan does not reach
 * this package and is not meant to: the module is opt-in, the same way {@code common-amqp} is. The
 * two stores it needs are declared here too, so a service turns webhooks on with one annotation and
 * does not have to know that one of the two repositories is a Mongo one.</p>
 *
 * <h2>The entity scan is the sharp edge</h2>
 *
 * <p>{@code @EntityScan} is additive across configurations, but it <strong>replaces</strong> Spring
 * Boot's default, which is the package of the application class. So a service that declares
 * {@link EnableStripeWebhooks} and nothing else loses its own entities - every one of them, at
 * startup, loudly. It has to name its own package in an {@code @EntityScan} of its own, which
 * {@code PaymentsApplication} does and says why.</p>
 *
 * <p>The exclude filter is for the same reason the one it replaced existed: the JPA scan is this
 * module's whole package and the archived body's repository is inside it, so it is taken out by
 * type rather than left to Spring Data to guess which store owns it.</p>
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(WebhookProperties.class)
@EntityScan(basePackageClasses = {StripeEvent.class, StripeEventPayload.class})
@EnableJpaRepositories(
        basePackageClasses = StripeEventRepository.class,
        excludeFilters = @ComponentScan.Filter(
                type = FilterType.ASSIGNABLE_TYPE,
                classes = StripeEventPayloadRepository.class))
@EnableMongoRepositories(basePackageClasses = StripeEventPayloadRepository.class)
public class WebhookConfiguration {

    /**
     * The claim, mark and failure queries.
     *
     * <p>Hand-written SQL on a {@code NamedParameterJdbcTemplate} Spring Boot already built: the
     * claim is a {@code SELECT ... FOR UPDATE SKIP LOCKED}, which has no JPA spelling, and it is
     * what lets several instances sweep the same ledger without applying an event twice.</p>
     *
     * @param jdbc the template, from the service's own data source
     * @return the queries
     */
    @Bean
    public StripeEventClaimRepository stripeEventClaimRepository(NamedParameterJdbcTemplate jdbc) {
        return new StripeEventClaimRepository(jdbc);
    }

    /**
     * Writes an event down before anything acts on it.
     *
     * @param repository the ledger table
     * @param objectMapper the service's mapper, for the columns read out of the event
     * @return the ledger
     */
    @Bean
    public StripeEventLedger stripeEventLedger(
            StripeEventRepository repository, ObjectMapper objectMapper) {
        return new StripeEventLedger(repository, objectMapper);
    }

    /**
     * Verification and recording: everything the endpoint does before it answers 200.
     *
     * @param ledger the ledger
     * @param payloads the archived bodies
     * @param properties the two signing secrets, one per endpoint
     * @return the service
     */
    @Bean
    public StripeWebhookService stripeWebhookService(
            StripeEventLedger ledger,
            StripeEventPayloadRepository payloads,
            StripeProperties properties) {
        return new StripeWebhookService(ledger, payloads, properties);
    }

    /**
     * The two endpoints themselves.
     *
     * <p>A declared bean rather than a scanned {@code @RestController}, which changes nothing about
     * how Spring MVC finds its routes: the handler mapping reads them off any bean whose class
     * carries {@code @RequestMapping}, however that bean came to exist.</p>
     *
     * @param service what the endpoints call
     * @return the controller
     */
    @Bean
    public StripeWebhookController stripeWebhookController(StripeWebhookService service) {
        return new StripeWebhookController(service);
    }
}
