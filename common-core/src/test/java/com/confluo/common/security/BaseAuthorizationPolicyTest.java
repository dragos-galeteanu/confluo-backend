package com.confluo.common.security;

import com.confluo.common.filter.PaginationFilter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

/**
 * Tests for the default methods of {@link BaseAuthorizationPolicy}. The five abstract checks are a
 * resource's own business, so the implementation below only exists to reach the defaults.
 */
class BaseAuthorizationPolicyTest {

    /** the smallest policy that compiles, delegating each abstract check to a default method */
    private static final class TestPolicy implements BaseAuthorizationPolicy<String, String> {

        @Override
        public boolean canCreate(Authentication authentication, String request) {
            return isAdmin(authentication);
        }

        @Override
        public boolean canGetById(Authentication authentication, UUID id) {
            return isCurrentUser(authentication, id);
        }

        @Override
        public boolean canGetAll(Authentication authentication, PaginationFilter filter) {
            return isAuthenticated(authentication);
        }

        @Override
        public boolean canUpdate(Authentication authentication, String request) {
            return isAdminOrSupport(authentication);
        }

        @Override
        public boolean canDelete(Authentication authentication, UUID id) {
            return isAdmin(authentication);
        }
    }

    private final TestPolicy policy = new TestPolicy();

    private static Authentication with(String... authorities) {
        return new UsernamePasswordAuthenticationToken("user", "n/a",
                Arrays.stream(authorities).map(SimpleGrantedAuthority::new).toList());
    }

    private static Jwt.Builder jwt() {
        return Jwt.withTokenValue("token").header("alg", "none");
    }

    private static Authentication authenticationFor(Jwt jwt) {
        return new UsernamePasswordAuthenticationToken(jwt, "n/a", List.of());
    }

    @Test
    void toRoleAuthorityPrefixesAndUppercasesTheRoleName() {
        assertThat(policy.toRoleAuthority(UserRole.Admin)).isEqualTo("ROLE_ADMIN");
        assertThat(policy.toRoleAuthority(UserRole.Inspector)).isEqualTo("ROLE_INSPECTOR");
    }

    @ParameterizedTest
    @CsvSource({
            "admin,       ROLE_ADMIN",
            "Admin,       ROLE_ADMIN",
            "ROLE_ADMIN,  ROLE_ADMIN",
            "role_admin,  ROLE_ADMIN",
            "SCOPE_read,  SCOPE_READ",
            "scope_read,  SCOPE_READ"
    })
    void normalizeRoleAuthorityAddsThePrefixOnlyWhenItIsMissing(String authority, String expected) {
        assertThat(policy.normalizeRoleAuthority(authority)).isEqualTo(expected);
    }

    @Test
    void normalizeRoleAuthorityTurnsNullIntoTheEmptyString() {
        assertThat(policy.normalizeRoleAuthority(null)).isEmpty();
    }

    @Test
    void hasAnyAuthorityMatchesARoleRegardlessOfHowItIsSpelled() {
        assertThat(policy.hasAnyAuthority(with("ROLE_ADMIN"), Set.of(UserRole.Admin))).isTrue();
        assertThat(policy.hasAnyAuthority(with("admin"), Set.of(UserRole.Admin))).isTrue();
        assertThat(policy.hasAnyAuthority(with("Admin"), Set.of(UserRole.Admin))).isTrue();
    }

    @Test
    void hasAnyAuthorityNeedsOnlyOneOfTheRoles() {
        assertThat(policy.hasAnyAuthority(with("ROLE_SUPPORT"), Set.of(UserRole.Admin, UserRole.Support)))
                .isTrue();
    }

    @Test
    void hasAnyAuthorityRejectsAnUnrelatedRole() {
        assertThat(policy.hasAnyAuthority(with("ROLE_CUSTOMER"), Set.of(UserRole.Admin))).isFalse();
    }

    @Test
    void aScopeIsNotTreatedAsARole() {
        assertThat(policy.hasAnyAuthority(with("SCOPE_ADMIN"), Set.of(UserRole.Admin))).isFalse();
    }

    @Test
    void hasAnyAuthorityDeniesWithoutAnAuthentication() {
        assertThat(policy.hasAnyAuthority(null, Set.of(UserRole.Admin))).isFalse();
    }

    @Test
    void hasAnyAuthorityDeniesWhenTheAuthoritiesAreNull() {
        Authentication authentication = mock(Authentication.class);
        given(authentication.getAuthorities()).willReturn(null);

        assertThat(policy.hasAnyAuthority(authentication, Set.of(UserRole.Admin))).isFalse();
    }

    @Test
    void hasAnyAuthorityDeniesWhenTheUserHasNoAuthorities() {
        assertThat(policy.hasAnyAuthority(with(), Set.of(UserRole.Admin))).isFalse();
    }

    @Test
    void theRoleShortcutsAcceptTheirOwnRole() {
        assertThat(policy.isAdmin(with("ROLE_ADMIN"))).isTrue();
        assertThat(policy.isSupport(with("ROLE_SUPPORT"))).isTrue();
        assertThat(policy.isInspector(with("ROLE_INSPECTOR"))).isTrue();
        assertThat(policy.isSales(with("ROLE_SALES"))).isTrue();
    }

    @Test
    void theRoleShortcutsRejectAnotherRole() {
        assertThat(policy.isAdmin(with("ROLE_SUPPORT"))).isFalse();
        assertThat(policy.isSupport(with("ROLE_ADMIN"))).isFalse();
        assertThat(policy.isInspector(with("ROLE_ADMIN"))).isFalse();
        assertThat(policy.isSales(with("ROLE_ADMIN"))).isFalse();
    }

    @Test
    void isAdminOrSupportAcceptsEitherAndRejectsTheRest() {
        assertThat(policy.isAdminOrSupport(with("ROLE_ADMIN"))).isTrue();
        assertThat(policy.isAdminOrSupport(with("ROLE_SUPPORT"))).isTrue();
        assertThat(policy.isAdminOrSupport(with("ROLE_INSPECTOR"))).isFalse();
    }

    @Test
    void isAdminOrSupportOrInspectorAcceptsAllThreeAndRejectsTheRest() {
        assertThat(policy.isAdminOrSupportOrInspector(with("ROLE_ADMIN"))).isTrue();
        assertThat(policy.isAdminOrSupportOrInspector(with("ROLE_SUPPORT"))).isTrue();
        assertThat(policy.isAdminOrSupportOrInspector(with("ROLE_INSPECTOR"))).isTrue();
        assertThat(policy.isAdminOrSupportOrInspector(with("ROLE_SALES"))).isFalse();
    }

    @Test
    void disablingIsReservedForAdminAndSupport() {
        assertThat(policy.canDisable(with("ROLE_ADMIN"))).isTrue();
        assertThat(policy.canDisable(with("ROLE_SUPPORT"))).isTrue();
        assertThat(policy.canDisable(with("ROLE_INSPECTOR"))).isFalse();
        assertThat(policy.canDisable(null)).isFalse();
    }

    @Test
    void isAuthenticatedFollowsTheAuthenticationFlag() {
        assertThat(policy.isAuthenticated(null)).isFalse();
        assertThat(policy.isAuthenticated(with("ROLE_ADMIN"))).isTrue();

        UsernamePasswordAuthenticationToken unauthenticated =
                new UsernamePasswordAuthenticationToken("user", "n/a");
        assertThat(policy.isAuthenticated(unauthenticated)).isFalse();
    }

    @Test
    void isCurrentUserMatchesTheUserIdClaim() {
        UUID userId = UUID.randomUUID();
        Authentication authentication = authenticationFor(jwt().claim("userId", userId.toString()).build());

        assertThat(policy.isCurrentUser(authentication, userId)).isTrue();
    }

    @Test
    void isCurrentUserRejectsADifferentUserIdClaim() {
        Authentication authentication = authenticationFor(
                jwt().claim("userId", UUID.randomUUID().toString()).build());

        assertThat(policy.isCurrentUser(authentication, UUID.randomUUID())).isFalse();
    }

    @Test
    void isCurrentUserFallsBackToTheSubject() {
        UUID userId = UUID.randomUUID();
        Authentication authentication = authenticationFor(jwt().subject(userId.toString()).build());

        assertThat(policy.isCurrentUser(authentication, userId)).isTrue();
    }

    @Test
    void isCurrentUserFallsBackToTheSubjectWhenTheClaimIsBlank() {
        UUID userId = UUID.randomUUID();
        Authentication authentication = authenticationFor(
                jwt().claim("userId", "  ").subject(userId.toString()).build());

        assertThat(policy.isCurrentUser(authentication, userId)).isTrue();
    }

    @Test
    void aMalformedUserIdClaimDeniesWithoutTryingTheSubject() {
        UUID userId = UUID.randomUUID();
        Authentication authentication = authenticationFor(
                jwt().claim("userId", "not-a-uuid").subject(userId.toString()).build());

        assertThat(policy.isCurrentUser(authentication, userId)).isFalse();
    }

    @Test
    void aMalformedSubjectDenies() {
        Authentication authentication = authenticationFor(jwt().subject("not-a-uuid").build());

        assertThat(policy.isCurrentUser(authentication, UUID.randomUUID())).isFalse();
    }

    @Test
    void aJwtWithoutAUserIdOrSubjectDenies() {
        Authentication authentication = authenticationFor(jwt().claim("role", "Admin").build());

        assertThat(policy.isCurrentUser(authentication, UUID.randomUUID())).isFalse();
    }

    @Test
    void aBlankSubjectDenies() {
        Authentication authentication = authenticationFor(jwt().subject("   ").build());

        assertThat(policy.isCurrentUser(authentication, UUID.randomUUID())).isFalse();
    }

    @Test
    void isCurrentUserDeniesWhenThePrincipalIsNotAJwt() {
        assertThat(policy.isCurrentUser(with("ROLE_ADMIN"), UUID.randomUUID())).isFalse();
    }

    @Test
    void isCurrentUserDeniesWhenThePrincipalIsNull() {
        Authentication authentication = mock(Authentication.class);
        given(authentication.getPrincipal()).willReturn(null);

        assertThat(policy.isCurrentUser(authentication, UUID.randomUUID())).isFalse();
    }

    @Test
    void isCurrentUserDeniesWithoutAnAuthenticationOrAUserId() {
        assertThat(policy.isCurrentUser(null, UUID.randomUUID())).isFalse();
        assertThat(policy.isCurrentUser(with("ROLE_ADMIN"), null)).isFalse();
    }

    @Test
    void theAbstractChecksAreWiredToTheDefaults() {
        UUID userId = UUID.randomUUID();
        Authentication admin = with("ROLE_ADMIN");

        assertThat(policy.canCreate(admin, "request")).isTrue();
        assertThat(policy.canUpdate(admin, "request")).isTrue();
        assertThat(policy.canDelete(admin, userId)).isTrue();
        assertThat(policy.canGetAll(admin, new PaginationFilter(null, null, null, null))).isTrue();
        assertThat(policy.canGetById(authenticationFor(jwt().claim("userId", userId.toString()).build()), userId))
                .isTrue();
    }
}
