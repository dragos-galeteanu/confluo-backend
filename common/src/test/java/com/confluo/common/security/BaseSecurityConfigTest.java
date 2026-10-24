package com.confluo.common.security;

import org.junit.jupiter.api.Test;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.util.matcher.RequestMatcher;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for the parts of {@link BaseSecurityConfig} that need no servlet context. The filter chain
 * itself is covered by {@link BaseSecurityConfigFilterChainTest}.
 */
class BaseSecurityConfigTest {

    /** a module that customises nothing */
    private static final class PlainConfig implements BaseSecurityConfig {
    }

    /** a module that opens a path of its own */
    private static final class ExtraMatchersConfig implements BaseSecurityConfig {

        @Override
        public List<RequestMatcher> additionalPermitAllMatchers() {
            return List.of(request -> true);
        }
    }

    private final PlainConfig config = new PlainConfig();

    @Test
    void nothingIsPermittedBeyondTheBuiltInPathsByDefault() {
        assertThat(config.additionalPermitAllMatchers()).isEmpty();
    }

    @Test
    void aModuleCanAddItsOwnPermittedMatchers() {
        assertThat(new ExtraMatchersConfig().additionalPermitAllMatchers()).hasSize(1);
    }

    @Test
    void theJwtConverterIsAJwtAuthenticationConverter() {
        Converter<Jwt, ? extends AbstractAuthenticationToken> converter = config.jwtAuthenticationConverter();

        assertThat(converter).isInstanceOf(JwtAuthenticationConverter.class);
    }

    @Test
    void theJwtConverterReadsTheFirebaseRoleClaim() {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "none")
                .subject("user-1")
                .claim("role", "Admin")
                .claim("scope", "read")
                .build();

        AbstractAuthenticationToken authentication = config.jwtAuthenticationConverter().convert(jwt);

        assertThat(authentication).isNotNull();
        assertThat(authentication.getAuthorities())
                .extracting(GrantedAuthority::getAuthority)
                .containsExactlyInAnyOrder("ROLE_ADMIN", "SCOPE_read");
    }

    @Test
    void eachCallBuildsItsOwnConverter() {
        assertThat(config.jwtAuthenticationConverter()).isNotSameAs(config.jwtAuthenticationConverter());
    }
}
