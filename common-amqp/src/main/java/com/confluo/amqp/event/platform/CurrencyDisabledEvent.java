package com.confluo.amqp.event.platform;

import com.confluo.amqp.event.DomainEvent;

import java.time.Instant;
import java.util.UUID;

/**
 * A currency the platform will no longer price in.
 *
 * <p>Disabled is not deleted: the currency still exists and is still listed, and amounts already
 * recorded in it are still valid. What a consumer is being told is to stop offering it as a
 * choice.</p>
 *
 * <p>The id and the time, and nothing else. Anything further about the currency is the platform's
 * to answer, and a copy carried here would be a second place for it to be true. Starting small is
 * also the safer default, because versioning is asymmetric: adding a field later is additive and
 * needs no version bump, while taking one away that consumers have begun reading is a breaking
 * change.</p>
 *
 * @param currencyId the currency's id, which is also the envelope's aggregate id
 * @param disabledAt when it stopped being offered
 */
@DomainEvent(aggregate = "currency", event = "disabled")
public record CurrencyDisabledEvent(UUID currencyId, Instant disabledAt) {
}
