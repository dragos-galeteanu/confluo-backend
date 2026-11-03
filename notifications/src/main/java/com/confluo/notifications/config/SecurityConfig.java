package com.confluo.notifications.config;

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
 * Security for the notifications service.
 *
 * <p>The chain itself is the shared one: stateless, Firebase tokens, documentation and health open.
 * {@code @EnableMethodSecurity} is what makes the {@code @PreAuthorize} rules on the controllers
 * run - without it they are silently ignored and every caller can write.</p>
 *
 * <p>Nothing of this service's own is readable without a token. Unlike the platform's reference
 * data, none of what is here is public: a delivery log says who was told what and when, and the
 * templates are the wording of every message the platform sends.</p>
 *
 * <p>Three kinds of caller will eventually need a way in that a Firebase user token cannot
 * describe, and each arrives with the feature that needs it rather than now: the provider webhooks,
 * which carry the provider's own signature; the unsubscribe link, which is clicked from a mail
 * client by someone who is not signed in; and another service asking for a message to be sent,
 * which has no user token to forward.</p>
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
     * Opens the liveness probe.
     *
     * <p>{@code common-core} permits {@code /actuator/health} exactly, and nothing beneath it, so
     * the health group the container's healthcheck watches would otherwise answer the probe with a
     * 401 and the container would never come up. The group is defined in {@code application.yml},
     * and reports on PostgreSQL alone - see the Dockerfile for why the aggregate is the wrong thing
     * to hold a container's rotation on here.</p>
     *
     * @return the liveness probe's matcher
     */
    @Override
    public List<RequestMatcher> additionalPermitAllMatchers() {
        return List.of(new AntPathRequestMatcher("/actuator/health/liveness", HttpMethod.GET.name()));
    }
}
