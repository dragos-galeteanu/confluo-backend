package com.confluo.common.security;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * The same filter chain as {@link BaseSecurityConfigFilterChainTest}, for a module that adds no
 * permitted matchers of its own - the shape every service starts from.
 */
@SpringBootTest
@AutoConfigureMockMvc
class BaseSecurityConfigDefaultChainTest {

    @SpringBootApplication
    @EnableWebSecurity
    static class TestApplication implements BaseSecurityConfig {

        @Bean
        SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
            return filterChain(http);
        }

        @Bean
        JwtDecoder jwtDecoder() {
            return mock(JwtDecoder.class);
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @Test
    void everythingUnlistedStillNeedsAuthentication() throws Exception {
        mockMvc.perform(get("/widgets")).andExpect(result ->
                assertThat(result.getResponse().getStatus()).isEqualTo(401));
    }

    @Test
    void theBuiltInPathsAreOpenWithoutAnyExtraMatchers() throws Exception {
        mockMvc.perform(get("/actuator/health")).andExpect(result ->
                assertThat(result.getResponse().getStatus()).isNotEqualTo(401));
        mockMvc.perform(get("/api-docs/v1")).andExpect(result ->
                assertThat(result.getResponse().getStatus()).isNotEqualTo(401));
    }

    @Test
    void aPathAnotherModuleWouldOpenIsStillClosedHere() throws Exception {
        mockMvc.perform(get("/public/anything")).andExpect(result ->
                assertThat(result.getResponse().getStatus()).isEqualTo(401));
    }
}
