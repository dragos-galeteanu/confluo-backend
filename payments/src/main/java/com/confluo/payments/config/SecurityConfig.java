package com.confluo.payments.config;

import com.confluo.common.security.BaseSecurityConfig;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;

import java.util.List;

/**
 * Security for the payments service.
 *
 * <p>The chain itself is the shared one: stateless, Firebase tokens, documentation and health open.
 * {@code @EnableMethodSecurity} is what makes the {@code @PreAuthorize} rules on the controllers
 * run - without it they are silently ignored and every caller can write.</p>
 *
 * <p>Nothing of this service's own is readable without a token, and that bar is higher here than
 * anywhere else in the platform: these rows say what a person paid, what a provider earned, and
 * what Stripe thinks of their identity documents.</p>
 *
 * <p>The two Stripe webhook routes are the exception, and the only routes here no token can reach.
 * They carry Stripe's own signature instead, and {@code StripeWebhookService} verifies every
 * request against the signing secret for the endpoint it arrived at before anything is written -
 * which is the whole reason they may be permitted at all.</p>
 *
 * <p>One kind of caller still has no way in: <strong>another service asking for a charge or a
 * refund</strong>, which has no user token to forward. The notifications service has the same
 * unsolved problem, and it wants one answer for both rather than something invented here.</p>
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig implements BaseSecurityConfig {

    /**
     * The service's filter chain.
     * @param http the security builder
     * @return the chain
     * @throws Exception if the chain cannot be built
     */
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        return filterChain(http);
    }

    /**
     * Opens the liveness probe and the two Stripe webhook routes.
     *
     * <p>The probe, because {@code common-core} permits {@code /actuator/health} exactly and
     * nothing beneath it, so the health group the container's healthcheck watches would otherwise
     * answer with a 401 and the container would never come up. The group is defined in
     * {@code application.yml} and reports on PostgreSQL alone - see the Dockerfile for why the
     * aggregate is the wrong thing to hold a container's rotation on here.</p>
     *
     * <p>The webhooks, because Stripe has no Firebase token to send. Each is matched
     * <strong>exactly and for POST only</strong> rather than as a prefix: a {@code /webhooks/**}
     * pattern would also permit anything added under that path later, and the next thing added
     * under it might not be verifying anything.</p>
     *
     * @return the matchers for the probe and the two webhook routes
     */
    @Override
    public List<RequestMatcher> additionalPermitAllMatchers() {
        return List.of(
                new AntPathRequestMatcher("/actuator/health/liveness", HttpMethod.GET.name()),
                new AntPathRequestMatcher("/api/payments/webhooks/stripe", HttpMethod.POST.name()),
                new AntPathRequestMatcher("/api/payments/webhooks/stripe/connect", HttpMethod.POST.name()));
    }
}
