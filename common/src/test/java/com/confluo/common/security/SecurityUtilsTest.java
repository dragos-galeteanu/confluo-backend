package com.confluo.common.security;

import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for {@link SecurityUtils}, which reads the platform user id out of a Firebase JWT.
 */
class SecurityUtilsTest {

    private static Jwt.Builder jwt() {
        return Jwt.withTokenValue("token").header("alg", "none");
    }

    private static Authentication authenticationFor(Jwt jwt) {
        return new UsernamePasswordAuthenticationToken(jwt, "n/a", List.of());
    }

    @Test
    void theUserIdClaimIsPreferred() {
        UUID userId = UUID.randomUUID();
        Authentication authentication = authenticationFor(
                jwt().claim("userId", userId.toString()).subject(UUID.randomUUID().toString()).build());

        assertThat(SecurityUtils.extractUserId(authentication)).isEqualTo(userId);
    }

    @Test
    void theSubjectIsUsedWhenThereIsNoUserIdClaim() {
        UUID subject = UUID.randomUUID();
        Authentication authentication = authenticationFor(jwt().subject(subject.toString()).build());

        assertThat(SecurityUtils.extractUserId(authentication)).isEqualTo(subject);
    }

    @Test
    void aBlankUserIdClaimFallsBackToTheSubject() {
        UUID subject = UUID.randomUUID();
        Authentication authentication = authenticationFor(
                jwt().claim("userId", "   ").subject(subject.toString()).build());

        assertThat(SecurityUtils.extractUserId(authentication)).isEqualTo(subject);
    }

    @Test
    void aMalformedUserIdClaimFallsBackToTheSubject() {
        UUID subject = UUID.randomUUID();
        Authentication authentication = authenticationFor(
                jwt().claim("userId", "not-a-uuid").subject(subject.toString()).build());

        assertThat(SecurityUtils.extractUserId(authentication)).isEqualTo(subject);
    }

    @Test
    void aMalformedUserIdAndSubjectAreRefused() {
        Authentication authentication = authenticationFor(
                jwt().claim("userId", "not-a-uuid").subject("also-not-a-uuid").build());

        assertThatThrownBy(() -> SecurityUtils.extractUserId(authentication))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("complete user registration first");
    }

    @Test
    void aBlankSubjectIsRefused() {
        Authentication authentication = authenticationFor(jwt().subject("   ").build());

        assertThatThrownBy(() -> SecurityUtils.extractUserId(authentication))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void aJwtWithNeitherClaimIsRefused() {
        Authentication authentication = authenticationFor(jwt().claim("role", "Admin").build());

        assertThatThrownBy(() -> SecurityUtils.extractUserId(authentication))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void anAuthenticationWhosePrincipalIsNotAJwtIsRefused() {
        Authentication authentication =
                new UsernamePasswordAuthenticationToken("plain-user", "n/a", List.of());

        assertThatThrownBy(() -> SecurityUtils.extractUserId(authentication))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void noAuthenticationAtAllIsRefused() {
        assertThatThrownBy(() -> SecurityUtils.extractUserId(null))
                .isInstanceOf(AccessDeniedException.class);
    }
}
