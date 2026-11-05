package com.confluo.stripe;

import com.stripe.Stripe;
import com.stripe.StripeClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The one client everything reaches Stripe through.
 *
 * <p>A single bean rather than the library's static global state. {@code Stripe.apiKey} is a
 * mutable static, which makes acting as a connected account - or testing against anything - a
 * matter of hoping nothing else changed it meanwhile; the client carries its key and its options
 * with it, and {@code withStripeContext} derives a per-account client from this one without
 * touching it.</p>
 *
 * <h2>Why the API version is asserted rather than set</h2>
 *
 * <p>stripe-java has no way to pin an API version: it sends the version it was generated against
 * on every request, and its models exist to deserialise exactly that version's responses. The
 * dependency's version <strong>is</strong> the pin, which means a routine-looking bump in the pom
 * is a Stripe API upgrade - new fields, changed defaults, occasionally a different shape for
 * something this service reads.</p>
 *
 * <p>So the version is written down here as well, and disagreement is a startup failure. The same
 * bargain as {@code ddl-auto: validate}: the upgrade is still allowed, it just cannot happen
 * quietly. Bumping the pom means changing this constant too, and the diff is then where the review
 * of Stripe's changelog belongs.</p>
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(StripeProperties.class)
@Slf4j
public class StripeConfig {

    /**
     * The Stripe API version this service has been written and reviewed against, which must be the
     * one stripe-java pins. See the class javadoc for why this is a constant and not a setting.
     */
    public static final String EXPECTED_API_VERSION = "2026-10-28.endive";

    /**
     * How long to wait on a connection to Stripe, in milliseconds.
     *
     * <p>Five seconds, against the library's default of thirty. A connection that has not been
     * established in five seconds is not going to be, and the call it is holding up is usually one
     * a person is waiting on.</p>
     */
    private static final int CONNECT_TIMEOUT_MS = 5_000;

    /**
     * How long to wait on a response, in milliseconds.
     *
     * <p>Twenty seconds, against the library's default of eighty. Long enough for Stripe to do
     * real work - creating a session touches several objects - and short enough that a request
     * cannot sit on a thread for over a minute.</p>
     */
    private static final int READ_TIMEOUT_MS = 20_000;

    /**
     * How many times a failed request is retried by the library.
     *
     * <p>Safe to turn on, and not safe to raise much: stripe-java attaches an idempotency key to
     * the retries it issues itself, so a create that is retried after a timeout cannot charge
     * twice. That protection is the library's own and does not extend to a retry this service
     * issues, which is why every create written here passes an idempotency key of its own.</p>
     */
    private static final int MAX_NETWORK_RETRIES = 2;

    /**
     * Builds the client, and refuses to start if the library has moved to a different API version.
     *
     * @param properties the Stripe credentials
     * @return the client
     * @throws IllegalStateException if stripe-java pins an API version other than
     *                               {@link #EXPECTED_API_VERSION}, which means the dependency was
     *                               upgraded without the upgrade being looked at
     */
    @Bean
    public StripeClient stripeClient(StripeProperties properties) {
        if (!EXPECTED_API_VERSION.equals(Stripe.API_VERSION)) {
            throw new IllegalStateException(
                    ("stripe-java %s pins API version %s, but this service is written against %s. "
                            + "Review Stripe's changelog for what changed between them, then update "
                            + "StripeConfig.EXPECTED_API_VERSION.")
                            .formatted(Stripe.VERSION, Stripe.API_VERSION, EXPECTED_API_VERSION));
        }

        log.info("Stripe client configured [library={}, apiVersion={}]", Stripe.VERSION, Stripe.API_VERSION);

        return StripeClient.builder()
                .setApiKey(properties.secretKey())
                .setConnectTimeout(CONNECT_TIMEOUT_MS)
                .setReadTimeout(READ_TIMEOUT_MS)
                .setMaxNetworkRetries(MAX_NETWORK_RETRIES)
                .build();
    }
}
