package com.confluo.platform;

import com.confluo.common.exception.GlobalExceptionHandler;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.data.mongodb.config.EnableMongoAuditing;
import org.springframework.data.mongodb.repository.config.EnableMongoRepositories;

/**
 * The reference data every other service reads: countries, currencies, languages and feature flags.
 *
 * <p>This service talks to two stores. Spring Data has to decide which of its modules owns each
 * repository interface, and while it can usually work that out from the domain type, the two
 * packages are named explicitly below so that an ambiguity becomes a wrong setting rather than a
 * puzzling startup failure.</p>
 *
 * <p>{@code @EnableMongoAuditing} is what fills a document's created and updated timestamps; without
 * it they stay null, since MongoDB has no equivalent of Hibernate's generated values.</p>
 *
 * <p>{@link GlobalExceptionHandler} has to be imported by name. It lives in {@code common-core}, so
 * this service's component scan does not reach it, and the symptom of leaving it out is not a
 * startup failure but a quiet one: every handled exception becomes a 500 instead of the status the
 * handler would have given it. Each service that wants the shared error contract imports it.</p>
 */
@SpringBootApplication
@Import(GlobalExceptionHandler.class)
@EnableJpaRepositories(basePackages = "com.confluo.platform.repository.jpa")
@EnableMongoRepositories(basePackages = "com.confluo.platform.repository.mongo")
@EnableMongoAuditing
public class PlatformApplication {

    /**
     * Starts the service.
     * @param args the command line arguments
     */
    public static void main(String[] args) {
        SpringApplication.run(PlatformApplication.class, args);
    }
}
