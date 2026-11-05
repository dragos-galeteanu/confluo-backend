package com.confluo.common.security;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Tests the filter chain {@link BaseSecurityConfig} builds, by running real requests through it.
 * A permitted path answers 404 rather than 401 here, because nothing is mapped behind it - which is
 * exactly the distinction being asserted.
 */
@SpringBootTest
@AutoConfigureMockMvc
class BaseSecurityConfigFilterChainTest {

    @SpringBootApplication
    @EnableWebSecurity
    static class TestApplication implements BaseSecurityConfig {

        @Override
        public List<RequestMatcher> additionalPermitAllMatchers() {
            return List.of(new AntPathRequestMatcher("/public/**"));
        }

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
    void anUnauthenticatedRequestToAnythingElseIsRefused() throws Exception {
        mockMvc.perform(get("/widgets")).andExpect(result ->
                assertThat(result.getResponse().getStatus()).isEqualTo(401));
    }

    @Test
    void theDocumentationPathsAreOpen() throws Exception {
        for (String path : List.of("/api-docs/v1", "/swagger-ui/index.html", "/swagger-ui.html")) {
            mockMvc.perform(get(path)).andExpect(result ->
                    assertThat(result.getResponse().getStatus()).isNotEqualTo(401));
        }
    }

    @Test
    void theHealthEndpointIsOpen() throws Exception {
        mockMvc.perform(get("/actuator/health")).andExpect(result ->
                assertThat(result.getResponse().getStatus()).isNotEqualTo(401));
    }

    @Test
    void aModulesOwnPermittedPathIsOpen() throws Exception {
        mockMvc.perform(get("/public/anything")).andExpect(result ->
                assertThat(result.getResponse().getStatus()).isNotEqualTo(401));
    }

    @Test
    void csrfIsDisabledSoAPostNeedsNoToken() throws Exception {
        mockMvc.perform(post("/public/anything")).andExpect(result ->
                assertThat(result.getResponse().getStatus()).isNotEqualTo(403));
    }

    @Test
    void noSessionIsCreated() throws Exception {
        MvcResult result = mockMvc.perform(get("/widgets")).andReturn();

        assertThat(result.getRequest().getSession(false)).isNull();
    }
}
