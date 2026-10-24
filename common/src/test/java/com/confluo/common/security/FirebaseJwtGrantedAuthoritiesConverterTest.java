package com.confluo.common.security;

import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.Collection;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link FirebaseJwtGrantedAuthoritiesConverter}, which adds the Firebase "role" claim to
 * the scopes the default converter already reads.
 */
class FirebaseJwtGrantedAuthoritiesConverterTest {

    private final FirebaseJwtGrantedAuthoritiesConverter converter =
            new FirebaseJwtGrantedAuthoritiesConverter();

    private static Jwt.Builder jwt() {
        return Jwt.withTokenValue("token").header("alg", "none");
    }

    private Collection<String> authorities(Jwt jwt) {
        return converter.convert(jwt).stream().map(GrantedAuthority::getAuthority).toList();
    }

    @Test
    void theRoleClaimBecomesAPrefixedUppercaseAuthority() {
        assertThat(authorities(jwt().claim("role", "Admin").build())).containsExactly("ROLE_ADMIN");
    }

    @Test
    void anAlreadyPrefixedRoleIsNotPrefixedTwice() {
        assertThat(authorities(jwt().claim("role", "ROLE_SUPPORT").build()))
                .containsExactly("ROLE_SUPPORT");
    }

    @Test
    void aLowercasePrefixedRoleIsUppercasedWithoutDoublingThePrefix() {
        assertThat(authorities(jwt().claim("role", "role_sales").build()))
                .containsExactly("ROLE_SALES");
    }

    @Test
    void scopesAreKeptAlongsideTheRole() {
        assertThat(authorities(jwt().claim("scope", "read write").claim("role", "Admin").build()))
                .containsExactlyInAnyOrder("SCOPE_read", "SCOPE_write", "ROLE_ADMIN");
    }

    @Test
    void aJwtWithOnlyScopesYieldsOnlyScopes() {
        assertThat(authorities(jwt().claim("scope", "read").build())).containsExactly("SCOPE_read");
    }

    @Test
    void aJwtWithNothingToConvertYieldsNoAuthorities() {
        assertThat(authorities(jwt().claim("sub", "user").build())).isEmpty();
    }

    @Test
    void aBlankRoleClaimIsIgnored() {
        assertThat(authorities(jwt().claim("role", "   ").build())).isEmpty();
    }

    @Test
    void duplicateAuthoritiesAreCollapsed() {
        Collection<String> authorities =
                authorities(jwt().claim("scope", "ROLE_ADMIN").claim("role", "Admin").build());

        assertThat(authorities).containsExactlyInAnyOrder("SCOPE_ROLE_ADMIN", "ROLE_ADMIN");
    }
}
