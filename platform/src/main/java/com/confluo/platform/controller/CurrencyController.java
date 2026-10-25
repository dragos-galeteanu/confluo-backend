package com.confluo.platform.controller;

import com.confluo.common.controller.BaseController;
import com.confluo.common.dto.Paginated;
import com.confluo.common.filter.PaginationFilter;
import com.confluo.common.service.BaseService;
import com.confluo.platform.dto.currency.CurrencyCreateRequest;
import com.confluo.platform.dto.currency.CurrencyResponse;
import com.confluo.platform.dto.currency.CurrencyUpdateRequest;
import com.confluo.platform.entity.Currency;
import com.confluo.platform.service.CurrencyService;
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
 * Currencies the platform knows about.
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
@RequestMapping("/api/currencies")
@RequiredArgsConstructor
@Tag(name = "Currencies")
public class CurrencyController extends BaseController<Currency, CurrencyCreateRequest, CurrencyUpdateRequest, CurrencyResponse> {

    private final CurrencyService service;

    @Override
    protected BaseService<Currency, CurrencyCreateRequest, CurrencyUpdateRequest, CurrencyResponse> getService() {
        return service;
    }

    @Override
    @PostMapping
    @PreAuthorize("@currencyPolicy.canCreate(authentication, #request)")
    public ResponseEntity<CurrencyResponse> create(@Valid @RequestBody CurrencyCreateRequest request) {
        return super.create(request);
    }

    @Override
    @GetMapping("/{id}")
    @PreAuthorize("@currencyPolicy.canGetById(authentication, #id)")
    public ResponseEntity<CurrencyResponse> getById(@PathVariable UUID id) {
        return super.getById(id);
    }

    @Override
    @GetMapping
    @PreAuthorize("@currencyPolicy.canGetAll(authentication, #filter)")
    public ResponseEntity<Paginated<CurrencyResponse>> getAll(@Valid @ModelAttribute PaginationFilter filter) {
        return super.getAll(filter);
    }

    @Override
    @PutMapping
    @PreAuthorize("@currencyPolicy.canUpdate(authentication, #request)")
    public ResponseEntity<CurrencyResponse> update(@Valid @RequestBody CurrencyUpdateRequest request) {
        return super.update(request);
    }

    @Override
    @DeleteMapping("/{id}/disable")
    @PreAuthorize("@currencyPolicy.canDisable(authentication)")
    public ResponseEntity<CurrencyResponse> disable(@PathVariable UUID id) {
        return super.disable(id);
    }

    @Override
    @DeleteMapping("/{id}")
    @PreAuthorize("@currencyPolicy.canDelete(authentication, #id)")
    public ResponseEntity<CurrencyResponse> delete(@PathVariable UUID id) {
        return super.delete(id);
    }
}
