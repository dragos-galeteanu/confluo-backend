package com.confluo.platform.security;

import com.confluo.common.filter.PaginationFilter;
import com.confluo.common.security.BaseAuthorizationPolicy;
import com.confluo.platform.dto.country.CountryCreateRequest;
import com.confluo.platform.dto.country.CountryUpdateRequest;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Who may do what with country reference data.
 *
 * <p>Reading is open to everyone, with or without a token: the lists are the published ISO ones and
 * a client needs them before anyone has signed in. The read checks below are not consulted by the
 * controller, which leaves its read routes unguarded, but the interface requires them and they
 * state the same rule. Writing is reserved for administrators, including disabling:
 * {@code canDisable} would otherwise admit Support by default, and changing the platform's
 * reference data is not a support action.</p>
 */
@Component("countryPolicy")
public class CountryAuthorizationPolicy implements BaseAuthorizationPolicy<CountryCreateRequest, CountryUpdateRequest, UUID> {

    @Override
    public boolean canCreate(Authentication authentication, CountryCreateRequest request) {
        return isAdmin(authentication);
    }

    @Override
    public boolean canGetById(Authentication authentication, UUID id) {
        return true;
    }

    @Override
    public boolean canGetAll(Authentication authentication, PaginationFilter filter) {
        return true;
    }

    @Override
    public boolean canUpdate(Authentication authentication, CountryUpdateRequest request) {
        return isAdmin(authentication);
    }

    @Override
    public boolean canDelete(Authentication authentication, UUID id) {
        return isAdmin(authentication);
    }

    @Override
    public boolean canDisable(Authentication authentication) {
        return isAdmin(authentication);
    }
}
