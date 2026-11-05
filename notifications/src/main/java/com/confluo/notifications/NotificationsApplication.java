package com.confluo.notifications;

import com.confluo.amqp.EnableAmqp;
import com.confluo.common.exception.GlobalExceptionHandler;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.data.mongodb.config.EnableMongoAuditing;
import org.springframework.data.mongodb.repository.config.EnableMongoRepositories;

/**
 * Email and SMS people receive, and everything needed to decide whether they should.
 *
 * <p>Three stores, each holding a different kind of state. PostgreSQL holds the state machine -
 * recipients, their preferences, the suppression list and one row per message that should exist -
 * because a message waiting to be sent has to survive a restart and be claimed by exactly one
 * instance. MongoDB holds the content, because a template and a rendered body are documents whose
 * shape differs per channel and should not cost a migration to change. Redis holds counters and
 * short-lived locks, which are the one thing here that genuinely need not survive anything.</p>
 *
 * <p>The two Spring Data packages are named explicitly, as in {@code PlatformApplication}: the
 * module owning a repository interface can usually be inferred from its domain type, and saying it
 * outright turns an ambiguity into a wrong setting rather than a puzzling startup failure.
 * {@code @EnableMongoAuditing} is what fills a document's created and updated timestamps; without
 * it they stay null.</p>
 *
 * <p>{@link GlobalExceptionHandler} is imported by name because it lives in {@code common-core},
 * which this service's component scan does not reach. Leaving it out is not a startup failure but
 * a quiet one: every handled exception becomes a 500 instead of the status the handler would have
 * given it.</p>
 *
 * <p>{@code @EnableAmqp} turns on <strong>both</strong> halves of the messaging module, which is
 * what distinguishes this service from {@code platform}. It consumes other services' events - that
 * is how a notification comes to be asked for, and how the recipient table stays current - and it
 * publishes its own, because a message that turns out to be undeliverable is something the service
 * owning that contact needs to hear about. Neither half has anything to do yet: no handler is
 * declared, so no queue is, and nothing publishes.</p>
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@Import(GlobalExceptionHandler.class)
@EnableAmqp
@EnableJpaRepositories(basePackages = "com.confluo.notifications.repository.jpa")
@EnableMongoRepositories(basePackages = "com.confluo.notifications.repository.mongo")
@EnableMongoAuditing
public class NotificationsApplication {

    /**
     * Starts the service.
     * @param args the command line arguments
     */
    public static void main(String[] args) {
        SpringApplication.run(NotificationsApplication.class, args);
    }
}
