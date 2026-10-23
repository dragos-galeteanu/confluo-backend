package com.confluo.common.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.util.matcher.RequestMatcher;

import java.util.List;

/**
 * Base security configuration class for configuring security settings.
 */
public interface BaseSecurityConfig {

    Logger log = LoggerFactory.getLogger(BaseSecurityConfig.class);

    /**
     * Configures the security filter chain.
     * @param http the HttpSecurity object
     * @return the configured SecurityFilterChain
     * @throws Exception if an error occurs during configuration
     */
    default SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        List<RequestMatcher> extra = additionalPermitAllMatchers();
        log.info("Configuring security filter chain [additionalPermitAllMatchers={}]", extra.size());

        return http
                .csrf(AbstractHttpConfigurer::disable)
                .anonymous(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> {
                    auth.requestMatchers("/actuator/health").permitAll();
                    auth.requestMatchers("/api-docs/**").permitAll();
                    auth.requestMatchers("/swagger-ui/**").permitAll();
                    auth.requestMatchers("/swagger-ui.html").permitAll();
                    if (!extra.isEmpty()) {
                        log.debug("Permitting additional unauthenticated matchers [count={}]", extra.size());
                        auth.requestMatchers(extra.toArray(RequestMatcher[]::new)).permitAll();
                    }
                    auth.anyRequest().authenticated();
                })
                .oauth2ResourceServer(oauth ->
                        oauth.jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter()))
                )
                .build();
    }

    /**
     * Returns a converter that converts a JWT to an AbstractAuthenticationToken.
     * @return a converter that converts a JWT to an AbstractAuthenticationToken
     */
    default Converter<Jwt, ? extends AbstractAuthenticationToken> jwtAuthenticationConverter() {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(new FirebaseJwtGrantedAuthoritiesConverter());
        return converter;
    }

    /**
     * Additional request matchers that should be permitted without authentication.
     * @return a list of additional request matchers that should be permitted without authentication
     */
    default List<RequestMatcher> additionalPermitAllMatchers() {
        return List.of();
    }
}
