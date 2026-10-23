package com.confluo.common.security;

import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.UUID;

@Slf4j
public final class SecurityUtils {

    // Private constructor to prevent instantiation.
    private SecurityUtils() {}

    /**
     * Extracts the platform user ID from the JWT.
     * @param auth the authentication object.
     * @throws AccessDeniedException if the JWT does not contain a valid platform userId.
     * @return the platform user ID.
     */
    public static UUID extractUserId(Authentication auth) {
        if (auth != null && auth.getPrincipal() instanceof Jwt jwt) {
            String userId = jwt.getClaimAsString("userId");
            if (userId != null && !userId.isBlank()) {
                try {
                    return UUID.fromString(userId);
                } catch (IllegalArgumentException e) {
                    log.warn("Malformed userId claim in JWT [raw={}]", userId);
                }
            }
            String subject = jwt.getSubject();
            if (subject != null && !subject.isBlank()) {
                try {
                    return UUID.fromString(subject);
                } catch (IllegalArgumentException e) {
                    log.warn("Malformed subject in JWT [raw={}]", subject);
                }
            }
        } else {
            log.debug("Cannot extract userId: authentication is missing or principal is not a JWT");
        }
        log.warn("JWT does not contain a valid platform userId — complete user registration first");
        throw new AccessDeniedException(
                "JWT does not contain a valid platform userId — complete user registration first");
    }
}
