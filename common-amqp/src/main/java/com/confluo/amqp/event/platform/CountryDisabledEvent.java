package com.confluo.amqp.event.platform;

import com.confluo.amqp.event.DomainEvent;

import java.time.Instant;
import java.util.UUID;

/**
 * A country the platform will no longer offer as a choice.
 *
 * <p>Disabled is not deleted: the country still exists and is still listed, and addresses,
 * registrations and tax rules already referring to it are untouched. What a consumer is being told
 * is to stop presenting it as something new can be chosen.</p>
 *
 * <p>The id and the time, and nothing else. Anything further about the country - its codes, its VAT
 * rate, its memberships - is the platform's to answer, and a copy carried here would be a second
 * place for it to be true.</p>
 *
 * @param countryId the country's id, which is also the envelope's aggregate id
 * @param disabledAt when it stopped being offered
 */
@DomainEvent(aggregate = "country", event = "disabled")
public record CountryDisabledEvent(UUID countryId, Instant disabledAt) {
}
