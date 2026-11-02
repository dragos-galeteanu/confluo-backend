package com.confluo.amqp.event.platform;

import com.confluo.amqp.event.DomainEvent;

import java.time.Instant;
import java.util.UUID;

/**
 * A language the platform will no longer offer as a choice.
 *
 * <p>Disabled is not deleted: the language still exists and is still listed, and anything already
 * written or configured in it stays readable. What a consumer is being told is to stop presenting
 * it as something new can be chosen.</p>
 *
 * <p>The id and the time, and nothing else. Anything further about the language - its code, its
 * native name, its direction - is the platform's to answer, and a copy carried here would be a
 * second place for it to be true.</p>
 *
 * @param languageId the language's id, which is also the envelope's aggregate id
 * @param disabledAt when it stopped being offered
 */
@DomainEvent(aggregate = "language", event = "disabled")
public record LanguageDisabledEvent(UUID languageId, Instant disabledAt) {
}
