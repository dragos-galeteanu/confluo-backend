package com.confluo.common.security;

import com.confluo.common.filter.PaginationFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Base authorization policy interface for defining authorization roles.
 * @param <CREATE_REQUEST> type of the request object for creating a new resource
 * @param <UPDATE_REQUEST> type of the request object for updating an existing resource
 */
public interface BaseAuthorizationPolicy<CREATE_REQUEST, UPDATE_REQUEST> {

    Logger log = LoggerFactory.getLogger(BaseAuthorizationPolicy.class);

    /**
     * Check if the user has any of the Admin or Support roles.
     * @param authentication the authentication object
     * @return true if the user has any of the Admin or Support roles, false otherwise
     */
    default boolean isAdminOrSupport(Authentication authentication) {
        return hasAnyAuthority(authentication, Set.of(UserRole.Admin, UserRole.Support));
    }

    /**
     * Check if the user has any of the Admin, Support, or Inspector roles.
     * @param authentication the authentication object
     * @return true if the user has any of the Admin, Support, or Inspector roles, false otherwise
     */
    default boolean isAdminOrSupportOrInspector(Authentication authentication) {
        return hasAnyAuthority(authentication, Set.of(UserRole.Admin, UserRole.Support, UserRole.Inspector));
    }

    /**
     * Check if the user has the Admin role.
     * @param authentication the authentication object
     * @return true if the user has the Admin role, false otherwise
     */
    default boolean isAdmin(Authentication authentication) {
        return hasAnyAuthority(authentication, Set.of(UserRole.Admin));
    }

    /**
     * Check if the user has the Support role.
     * @param authentication the authentication object
     * @return true if the user has the Support role, false otherwise
     */
    default boolean isSupport(Authentication authentication) {
        return hasAnyAuthority(authentication, Set.of(UserRole.Support));
    }

    /**
     * Check if the user has the Inspector role.
     * @param authentication the authentication object
     * @return true if the user has the Inspector role, false otherwise
     */
    default boolean isInspector(Authentication authentication) {
        return hasAnyAuthority(authentication, Set.of(UserRole.Inspector));
    }

    /**
     * Check if the user has the Sales role.
     * @param authentication the authentication object
     * @return true if the user has the Sales role, false otherwise
     */
    default boolean isSales(Authentication authentication) {
        return hasAnyAuthority(authentication, Set.of(UserRole.Sales));
    }

    /**
     * Check if the user can create a new resource, based on the fields of the create request
     * (e.g. a foreign key identifying the parent/owning resource).
     * @param authentication the authentication object
     * @param request the request object containing the data for creating the resource
     * @return true if the user can create the resource described by the request, false otherwise
     */
    boolean canCreate(Authentication authentication, CREATE_REQUEST request);

    /**
     * Check if the user can get the resource by ID.
     * @param authentication the authentication object
     * @param id the id of the resource
     * @return true if the user can get the resource by ID, false otherwise
     */
    boolean canGetById(Authentication authentication, UUID id);

    /**
     * Check if the user can get all resources visible under the given filter
     * @param authentication the authentication object
     * @param filter the pagination filter used to list resources
     * @return true if the user can get all resources matching the filter, false otherwise
     */
    boolean canGetAll(Authentication authentication, PaginationFilter filter);

    /**
     * Check if the user can update the resource, based on the fields of the update request
     * (e.g. a foreign key identifying the parent/owning resource).
     * @param authentication the authentication object
     * @param request the request object containing the data for updating the resource
     * @return true if the user can update the resource described by the request, false otherwise
     */
    boolean canUpdate(Authentication authentication, UPDATE_REQUEST request);

    /**
     * Check if the user can delete the resource.
     * @param authentication the authentication object
     * @param id the id of the resource
     * @return true if the user can delete the resource, false otherwise
     */
    boolean canDelete(Authentication authentication, UUID id);

    /**
     * Check if the user can disable the resource.
     * @param authentication the authentication object
     * @return true if the user can disable the resource, false otherwise
     */
    default boolean canDisable(Authentication authentication) {
        return hasAnyAuthority(authentication, Set.of(UserRole.Admin, UserRole.Support));
    }

    /**
     * Check if the user has any of the specified authorities.
     * @param authentication the authentication object
     * @param authorities the set of authorities to check
     * @return true if the user has any of the specified authorities, false otherwise
     */
    default boolean hasAnyAuthority(Authentication authentication, Set<UserRole> authorities) {
        if (authentication == null || authentication.getAuthorities() == null) {
            log.debug("Authorization denied: no authentication or authorities present [required={}]", authorities);
            return false;
        }

        Set<String> expectedAuthorities = authorities.stream()
                .map(this::toRoleAuthority)
                .collect(Collectors.toSet());

        boolean granted = authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .map(this::normalizeRoleAuthority)
                .anyMatch(expectedAuthorities::contains);

        log.debug("Authorization check [user={}, required={}, granted={}]",
                authentication.getName(), expectedAuthorities, granted);
        return granted;
    }

    /**
     * Convert a UserRole enum to a role authority string.
     * @param role the UserRole enum to convert
     * @return the role authority string
     */
    default String toRoleAuthority(UserRole role) {
        return "ROLE_" + role.name().toUpperCase(Locale.ROOT);
    }

    /**
     * Normalize the role authority by adding the "ROLE_" prefix if it doesn't exist.
     * @param authority the role authority to normalize
     * @return the normalized role authority
     */
    default String normalizeRoleAuthority(String authority) {
        if (authority == null) {
            return "";
        }

        String normalized = authority.toUpperCase(Locale.ROOT);
        if (normalized.startsWith("ROLE_") || normalized.startsWith("SCOPE_")) {
            return normalized;
        }
        return "ROLE_" + normalized;
    }

    /**
     * Check if the user is authenticated.
     *
     * <p>An {@link AnonymousAuthenticationToken} does not count. It reports itself as
     * authenticated - that is how Spring Security models "nobody is signed in" where anonymous
     * authentication is enabled - and taking it at its word would hand an unauthenticated caller
     * everything behind this check.</p>
     *
     * @param authentication the authentication object
     * @return true if the user is authenticated, false otherwise
     */
    default boolean isAuthenticated(Authentication authentication) {
        return authentication != null
                && authentication.isAuthenticated()
                && !(authentication instanceof AnonymousAuthenticationToken);
    }

    /**
     * Check if the current user is the same as the provided user ID.
     * @param authentication the authentication object
     * @param userId the user ID to check
     * @return true if the current user is the same as the provided user ID, false otherwise
     */
    default boolean isCurrentUser(Authentication authentication, UUID userId) {
        // Check if authentication or userId is null
        if (authentication == null || userId == null) {
            // If either is null, return false
            log.debug("Current-user check denied: missing authentication or userId");
            return false;
        }

        // Check if the principal is a JWT
        Object principal = authentication.getPrincipal();
        if (!(principal instanceof Jwt jwt)) {
            // If the principal is not a JWT, return false
            log.debug("Current-user check denied: principal is not a JWT [type={}]",
                    principal == null ? "null" : principal.getClass().getSimpleName());
            return false;
        }

        // Check if the JWT contains a userId claim
        String claimedUserId = jwt.getClaimAsString("userId");
        if (claimedUserId != null && !claimedUserId.isBlank()) {
            try {
                // Compare the claimed userId with the provided userId
                return userId.equals(UUID.fromString(claimedUserId));
            } catch (IllegalArgumentException e) {
                // If the claimed userId is not a valid UUID, return false
                log.warn("Malformed userId claim in JWT [raw={}]", claimedUserId);
                return false;
            }
        }

        // If the JWT does not contain a userId claim, check the subject
        String subject = jwt.getSubject();
        // If the subject is null or blank, return false
        if (subject == null || subject.isBlank()) {
            log.debug("Current-user check denied: JWT has no userId claim or subject");
            return false;
        }
        try {
            // Compare the subject with the provided userId
            return userId.equals(UUID.fromString(subject));
        } catch (IllegalArgumentException ex) {
            // If the subject is not a valid UUID, return false
            log.warn("Malformed subject in JWT [raw={}]", subject);
            return false;
        }
    }
}
