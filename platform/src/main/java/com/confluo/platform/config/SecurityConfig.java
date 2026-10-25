package com.confluo.platform.config;

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
import java.util.stream.Stream;

/**
 * Security for the platform service.
 *
 * <p>The chain itself is the shared one: stateless, Firebase tokens, documentation and health open.
 * {@code @EnableMethodSecurity} is what makes the {@code @PreAuthorize} rules on the controllers
 * run - without it they are silently ignored and every caller can write.</p>
 *
 * <p>Reading the reference data needs no token. A sign-up form has to offer a list of countries
 * before anyone has an account, and none of it is private: it is the ISO lists. Writing is
 * unaffected and still reserved for administrators, so the matchers below are scoped to
 * {@code GET}.</p>
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig implements BaseSecurityConfig {

    /** the resources anyone may read */
    private static final List<String> PUBLIC_READ_PATHS =
            List.of("/api/countries", "/api/currencies", "/api/languages");

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
     * Opens the reference data to unauthenticated readers.
     *
     * <p>Both the collection and a single resource are listed: an {@code /api/countries/**}
     * pattern on its own would not be matched by a request for {@code /api/countries}.</p>
     *
     * @return the matchers permitted without a token
     */
    @Override
    public List<RequestMatcher> additionalPermitAllMatchers() {
        return PUBLIC_READ_PATHS.stream()
                .<RequestMatcher>flatMap(path -> Stream.of(
                        new AntPathRequestMatcher(path, HttpMethod.GET.name()),
                        new AntPathRequestMatcher(path + "/**", HttpMethod.GET.name())))
                .toList();
    }
}
