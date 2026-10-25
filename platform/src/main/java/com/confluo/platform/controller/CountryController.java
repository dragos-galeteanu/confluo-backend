package com.confluo.platform.controller;

import com.confluo.common.controller.BaseController;
import com.confluo.common.dto.Paginated;
import com.confluo.common.filter.PaginationFilter;
import com.confluo.common.service.BaseService;
import com.confluo.platform.dto.country.CountryCreateRequest;
import com.confluo.platform.dto.country.CountryResponse;
import com.confluo.platform.dto.country.CountryUpdateRequest;
import com.confluo.platform.entity.Country;
import com.confluo.platform.service.CountryService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Countries the platform knows about.
 *
 * <p>Every route is inherited from {@link BaseController}; the overrides exist only to attach the
 * authorization rule, and to state the mapping rather than rely on it being inherited.</p>
 *
 * <p>Every route carries a rule, the read routes included, even though reading is open to
 * everyone. Keeping them annotated means the policy is the only place authorization is decided:
 * closing reads later is a change to one method there, not a change to a controller and a security
 * configuration at the same time.</p>
 */
@RestController
@RequestMapping("/api/countries")
@RequiredArgsConstructor
@Tag(name = "Countries")
public class CountryController extends BaseController<Country, CountryCreateRequest, CountryUpdateRequest, CountryResponse> {

    private final CountryService service;

    @Override
    protected BaseService<Country, CountryCreateRequest, CountryUpdateRequest, CountryResponse> getService() {
        return service;
    }

    @Override
    @PostMapping
    @PreAuthorize("@countryPolicy.canCreate(authentication, #request)")
    public ResponseEntity<CountryResponse> create(@Valid @RequestBody CountryCreateRequest request) {
        return super.create(request);
    }

    @Override
    @GetMapping("/{id}")
    @PreAuthorize("@countryPolicy.canGetById(authentication, #id)")
    public ResponseEntity<CountryResponse> getById(@PathVariable UUID id) {
        return super.getById(id);
    }

    @Override
    @GetMapping
    @PreAuthorize("@countryPolicy.canGetAll(authentication, #filter)")
    public ResponseEntity<Paginated<CountryResponse>> getAll(@Valid @ModelAttribute PaginationFilter filter) {
        return super.getAll(filter);
    }

    @Override
    @PutMapping
    @PreAuthorize("@countryPolicy.canUpdate(authentication, #request)")
    public ResponseEntity<CountryResponse> update(@Valid @RequestBody CountryUpdateRequest request) {
        return super.update(request);
    }

    @Override
    @DeleteMapping("/{id}/disable")
    @PreAuthorize("@countryPolicy.canDisable(authentication)")
    public ResponseEntity<CountryResponse> disable(@PathVariable UUID id) {
        return super.disable(id);
    }

    @Override
    @DeleteMapping("/{id}")
    @PreAuthorize("@countryPolicy.canDelete(authentication, #id)")
    public ResponseEntity<CountryResponse> delete(@PathVariable UUID id) {
        return super.delete(id);
    }
}
