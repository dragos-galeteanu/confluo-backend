package com.confluo.common.security;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;

import java.util.Collection;
import java.util.HashSet;
import java.util.Locale;

/**
 * Custom converter to add the role claim from the JWT to the granted authorities.
 */
public class FirebaseJwtGrantedAuthoritiesConverter implements Converter<Jwt, Collection<GrantedAuthority>> {

    /**
     * Convert the JWT to a collection of granted authorities.
     * @param jwt the JWT to convert
     * @return the collection of granted authorities
     */
    @Override
    public Collection<GrantedAuthority> convert(Jwt jwt) {
        // Use the default scope converter to get the authorities from the JWT
        JwtGrantedAuthoritiesConverter scopeConverter = new JwtGrantedAuthoritiesConverter();
        Collection<GrantedAuthority> authorities = new HashSet<>(scopeConverter.convert(jwt));

        // Add the role claim to the authorities
        String role = jwt.getClaimAsString("role");
        if (role != null && !role.isBlank()) {
            // Normalize the role authority by adding the "ROLE_" prefix if it doesn't exist.
            // Locale.ROOT, not the JVM default: on a Turkish-locale server "admin" upper-cases to
            // "ADMİN" with a dotted capital I, and the resulting ROLE_ADMİN never matches the
            // ROLE_ADMIN that BaseAuthorizationPolicy builds - every admin silently loses access.
            String normalized = role.toUpperCase(Locale.ROOT);
            String normalizedRoleAuthority = normalized.startsWith("ROLE_") ? normalized : "ROLE_" + normalized;
            authorities.add(new SimpleGrantedAuthority(normalizedRoleAuthority));
        }

        return authorities;
    }
}
