package com.confluo.platform.controller;

import com.confluo.common.controller.BaseController;
import com.confluo.common.dto.Paginated;
import com.confluo.common.filter.PaginationFilter;
import com.confluo.common.service.BaseService;
import com.confluo.platform.dto.language.LanguageCreateRequest;
import com.confluo.platform.dto.language.LanguageResponse;
import com.confluo.platform.dto.language.LanguageUpdateRequest;
import com.confluo.platform.entity.Language;
import com.confluo.platform.service.LanguageService;
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
 * Languages the platform knows about.
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
@RequestMapping("/api/languages")
@RequiredArgsConstructor
@Tag(name = "Languages")
public class LanguageController extends BaseController<Language, LanguageCreateRequest, LanguageUpdateRequest, LanguageResponse> {

    private final LanguageService service;

    @Override
    protected BaseService<Language, LanguageCreateRequest, LanguageUpdateRequest, LanguageResponse> getService() {
        return service;
    }

    @Override
    @PostMapping
    @PreAuthorize("@languagePolicy.canCreate(authentication, #request)")
    public ResponseEntity<LanguageResponse> create(@Valid @RequestBody LanguageCreateRequest request) {
        return super.create(request);
    }

    @Override
    @GetMapping("/{id}")
    @PreAuthorize("@languagePolicy.canGetById(authentication, #id)")
    public ResponseEntity<LanguageResponse> getById(@PathVariable UUID id) {
        return super.getById(id);
    }

    @Override
    @GetMapping
    @PreAuthorize("@languagePolicy.canGetAll(authentication, #filter)")
    public ResponseEntity<Paginated<LanguageResponse>> getAll(@Valid @ModelAttribute PaginationFilter filter) {
        return super.getAll(filter);
    }

    @Override
    @PutMapping
    @PreAuthorize("@languagePolicy.canUpdate(authentication, #request)")
    public ResponseEntity<LanguageResponse> update(@Valid @RequestBody LanguageUpdateRequest request) {
        return super.update(request);
    }

    @Override
    @DeleteMapping("/{id}/disable")
    @PreAuthorize("@languagePolicy.canDisable(authentication)")
    public ResponseEntity<LanguageResponse> disable(@PathVariable UUID id) {
        return super.disable(id);
    }

    @Override
    @DeleteMapping("/{id}")
    @PreAuthorize("@languagePolicy.canDelete(authentication, #id)")
    public ResponseEntity<LanguageResponse> delete(@PathVariable UUID id) {
        return super.delete(id);
    }
}
