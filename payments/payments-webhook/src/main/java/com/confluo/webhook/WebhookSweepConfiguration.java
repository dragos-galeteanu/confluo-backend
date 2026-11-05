package com.confluo.webhook;

import com.confluo.webhook.payload.StripeEventPayloadRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.List;


/**
 * Puts the webhook sweep on a timer.
 *
 * <p>{@code @EnableScheduling} is here because the sweep has to run on one. Without it the beans
 * below are created, the endpoint keeps recording events perfectly, and nothing ever applies them -
 * which is the kind of failure that looks like everything working.</p>
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
public class WebhookSweepConfiguration {

    /**
     * The sweep.
     *
     * <p>{@code handlers} is every {@link StripeEventHandler} in the service, which Spring injects
     * as an empty list when there are none - the correct state while the handlers for individual
     * event types have not been written: every event is then recorded, found to have no handler,
     * and marked {@code IGNORED}.</p>
     *
     * @param claims the claim, mark and failure queries
     * @param events the ledger
     * @param payloads the archived bodies
     * @param properties the batch size, attempt cap and poll interval
     * @param transactionManager the service's transaction manager
     * @param handlers every handler declared in the service
     * @return the sweep
     */
    @Bean
    public StripeEventSweeper stripeEventSweeper(
            StripeEventClaimRepository claims,
            StripeEventRepository events,
            StripeEventPayloadRepository payloads,
            WebhookProperties properties,
            PlatformTransactionManager transactionManager,
            List<StripeEventHandler> handlers) {
        return new StripeEventSweeper(claims, events, payloads, properties, transactionManager, handlers);
    }

    /**
     * Schedules it.
     *
     * <p>Registered here rather than with {@code @Scheduled} on the method, for the reason
     * {@code OutboxConfiguration} gives: {@code @Scheduled} reads its interval as a raw string and
     * accepts only a plain number of milliseconds or an ISO-8601 duration, so an ordinary
     * {@code poll-interval: 5s} would stop the service at startup with "cannot parse into long".
     * Registering against the already-bound {@code Duration} accepts every form Spring Boot
     * does.</p>
     *
     * <p>A fixed <strong>delay</strong>, not a rate: the interval is the gap between a sweep
     * finishing and the next starting, so a sweep slower than the interval does not have another
     * queued up behind it.</p>
     *
     * @param sweeper the sweep
     * @param properties the poll interval
     * @return the registration
     */
    @Bean
    @ConditionalOnProperty(
            prefix = "confluo.webhook", name = "enabled", matchIfMissing = true)
    public SchedulingConfigurer stripeEventSweepSchedule(
            StripeEventSweeper sweeper, WebhookProperties properties) {
        return (ScheduledTaskRegistrar registrar) ->
                registrar.addFixedDelayTask(sweeper::sweep, properties.pollInterval());
    }

    /**
     * Reports whether events are being applied.
     *
     * <p>Named {@code stripeEvents}, which is what it appears as under
     * {@code /actuator/health}.</p>
     *
     * @param claims the counts it reads
     * @param properties how old is too old
     * @return the indicator
     */
    @Bean("stripeEvents")
    public StripeEventHealthIndicator stripeEventHealthIndicator(
            StripeEventClaimRepository claims, WebhookProperties properties) {
        return new StripeEventHealthIndicator(claims, properties);
    }
}
