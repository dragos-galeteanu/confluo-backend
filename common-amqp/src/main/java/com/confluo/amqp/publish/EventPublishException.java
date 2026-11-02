package com.confluo.amqp.publish;

/**
 * One attempt to get an event to the broker did not work.
 *
 * <p>Never fatal. The entry it came from stays in the outbox with the reason recorded, and the
 * drainer tries again - so this is a signal to the drainer rather than an error for a caller, and it
 * must never escape into the request that produced the event.</p>
 */
public class EventPublishException extends RuntimeException {

    /**
     * @param message what went wrong
     */
    public EventPublishException(String message) {
        super(message);
    }

    /**
     * @param message what went wrong
     * @param cause the underlying failure
     */
    public EventPublishException(String message, Throwable cause) {
        super(message, cause);
    }
}
