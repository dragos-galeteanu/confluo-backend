package com.confluo.payments.identity;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Where Stripe sends somebody after checking their identity documents.
 *
 * <p>One URL, because there is nothing to refresh: unlike an account link, a verification
 * session can be returned to while it is open, so a stale page is recovered by asking this
 * service for the session again rather than by Stripe bouncing the visitor somewhere.</p>
 *
 * @param returnUrl where Stripe sends them when they have finished submitting documents.
 *                  Reaching it means <strong>submitted</strong>, not verified: Stripe's checks
 *                  run afterwards and the outcome arrives on a webhook, which can be minutes
 *                  later. A page that reads arrival as success will tell somebody they are
 *                  verified when Stripe has not decided
 */
@Validated
@ConfigurationProperties(prefix = "confluo.payments.identity")
public record IdentityProperties(@NotBlank String returnUrl) {
}
