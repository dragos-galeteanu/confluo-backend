package com.confluo.amqp.consume;

import com.confluo.amqp.config.AmqpProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Instant;

/**
 * Keeps the consumed-message ledger from growing forever.
 *
 * <p>Nothing ever deletes from that table in the course of consuming, so without this it grows by
 * one row per event per handler for the life of the service. Daily, off-peak, and bounded only by
 * the retention window - which has to outlive the longest redelivery, including a message replayed
 * out of a parking lot days after it first failed.</p>
 */
@Slf4j
public class ConsumedMessagePurger {

    private final ConsumedMessageStore store;
    private final AmqpProperties properties;

    /**
     * @param store the ledger
     * @param properties the retention window
     */
    public ConsumedMessagePurger(ConsumedMessageStore store, AmqpProperties properties) {
        this.store = store;
        this.properties = properties;
    }

    /**
     * Forgets everything older than the retention window.
     */
    @Scheduled(cron = "${confluo.amqp.consumed-message.purge-cron:0 15 3 * * *}")
    public void purge() {
        Instant cutoff = Instant.now().minus(properties.consumedMessage().retention());
        try {
            int forgotten = store.purgeConsumedBefore(cutoff);
            if (forgotten > 0) {
                log.info("Forgot {} consumed message(s) from before {}", forgotten, cutoff);
            }
        } catch (RuntimeException e) {
            log.warn("Could not purge the consumed-message ledger; it will run again tomorrow: {}",
                    e.getMessage());
        }
    }
}
