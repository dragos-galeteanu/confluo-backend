package com.confluo.notifications.controller;

import com.confluo.common.controller.BaseController;
import com.confluo.common.dto.Paginated;
import com.confluo.common.filter.PaginationFilter;
import com.confluo.common.service.BaseService;
import com.confluo.notifications.dto.recipient.RecipientCreateRequest;
import com.confluo.notifications.dto.recipient.RecipientResponse;
import com.confluo.notifications.dto.recipient.RecipientUpdateRequest;
import com.confluo.notifications.entity.Recipient;
import com.confluo.notifications.service.RecipientService;
import io.swagger.v3.oas.annotations.Operation;
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
 * The people this service can reach.
 *
 * <p>Every route is inherited from {@link BaseController}; the overrides exist only to attach the
 * authorization rule, and to state the mapping rather than rely on it being inherited. The one
 * addition is the lookup by Firebase uid, which is the name every other service and every event
 * actually holds - an id of this service's own is something only this service knows.</p>
 *
 * <p>These routes are for an administrator correcting or seeding a row. The ordinary way a
 * recipient comes to exist is an event from whichever service owns users, and the ordinary way one
 * is created during a send is the pipeline resolving a uid it has not seen - neither comes through
 * here.</p>
 */
@RestController
@RequestMapping("/api/notifications/recipients")
@RequiredArgsConstructor
@Tag(name = "Recipients")
public class RecipientController extends BaseController<Recipient, RecipientCreateRequest, RecipientUpdateRequest, RecipientResponse> {

    private final RecipientService service;

    @Override
    protected BaseService<Recipient, RecipientCreateRequest, RecipientUpdateRequest, RecipientResponse> getService() {
        return service;
    }

    @Override
    @PostMapping
    @PreAuthorize("@recipientPolicy.canCreate(authentication, #request)")
    public ResponseEntity<RecipientResponse> create(@Valid @RequestBody RecipientCreateRequest request) {
        return super.create(request);
    }

    @Override
    @GetMapping("/{id}")
    @PreAuthorize("@recipientPolicy.canGetById(authentication, #id)")
    public ResponseEntity<RecipientResponse> getById(@PathVariable UUID id) {
        return super.getById(id);
    }

    /**
     * A recipient by their Firebase uid.
     *
     * <p>On its own path segment rather than as a query parameter on the collection, so that it
     * cannot be confused with a filter and cannot collide with the {@code /{id}} route: a uid is
     * not a UUID, and letting the two share a path would make the mapping depend on what the string
     * happened to look like.</p>
     *
     * @param firebaseUid the uid
     * @return the recipient
     */
    @GetMapping("/by-uid/{firebaseUid}")
    @Operation(summary = "Get a recipient by their Firebase uid")
    @PreAuthorize("@recipientPolicy.canGetByFirebaseUid(authentication, #firebaseUid)")
    public ResponseEntity<RecipientResponse> getByFirebaseUid(@PathVariable String firebaseUid) {
        return ResponseEntity.ok(service.getByFirebaseUid(firebaseUid));
    }

    @Override
    @GetMapping
    @PreAuthorize("@recipientPolicy.canGetAll(authentication, #filter)")
    public ResponseEntity<Paginated<RecipientResponse>> getAll(@Valid @ModelAttribute PaginationFilter filter) {
        return super.getAll(filter);
    }

    @Override
    @PutMapping
    @PreAuthorize("@recipientPolicy.canUpdate(authentication, #request)")
    public ResponseEntity<RecipientResponse> update(@Valid @RequestBody RecipientUpdateRequest request) {
        return super.update(request);
    }

    @Override
    @DeleteMapping("/{id}/disable")
    @PreAuthorize("@recipientPolicy.canDisable(authentication)")
    public ResponseEntity<RecipientResponse> disable(@PathVariable UUID id) {
        return super.disable(id);
    }

    @Override
    @DeleteMapping("/{id}")
    @PreAuthorize("@recipientPolicy.canDelete(authentication, #id)")
    public ResponseEntity<RecipientResponse> delete(@PathVariable UUID id) {
        return super.delete(id);
    }
}
