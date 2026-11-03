package com.confluo.notifications.controller;

import com.confluo.common.dto.Paginated;
import com.confluo.common.filter.PaginationFilter;
import com.confluo.notifications.channel.Channel;
import com.confluo.notifications.dto.template.NotificationTemplateCreateRequest;
import com.confluo.notifications.dto.template.NotificationTemplateResponse;
import com.confluo.notifications.dto.template.NotificationTemplateUpdateRequest;
import com.confluo.notifications.service.TemplateService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The wording of every message the platform sends.
 *
 * <p>Written out rather than inherited from {@link com.confluo.common.controller.BaseController},
 * and not because templates are unusual. That base addresses a resource by a {@code UUID} and
 * reaches it through a {@code UUID}-keyed repository; a document is keyed by an {@code ObjectId},
 * which {@code BaseEntity}'s own documentation explains is why the two stacks stay apart. The
 * routes are deliberately the same shape as the inherited ones, so that the only difference a
 * caller sees is the form of the identifier.</p>
 */
@RestController
@RequestMapping("/api/notification-templates")
@RequiredArgsConstructor
@Tag(name = "Notification templates")
public class NotificationTemplateController {

    private final TemplateService service;

    /**
     * Adds the wording of a message.
     * @param request the wording
     * @return it, as stored
     */
    @PostMapping
    @Operation(summary = "Add the wording of a message")
    @PreAuthorize("@templatePolicy.canCreate(authentication, #request)")
    public ResponseEntity<NotificationTemplateResponse> create(
            @Valid @RequestBody NotificationTemplateCreateRequest request) {
        return new ResponseEntity<>(service.create(request), HttpStatus.CREATED);
    }

    /**
     * A template by its id.
     * @param id the 24-character hex string
     * @return the template
     */
    @GetMapping("/{id}")
    @Operation(summary = "Get a template by id")
    @PreAuthorize("@templatePolicy.canGetById(authentication, #id)")
    public ResponseEntity<NotificationTemplateResponse> getById(@PathVariable String id) {
        return ResponseEntity.ok(service.getById(id));
    }

    /**
     * Every template matching a filter.
     * @param filter pagination, filter, and sort parameters
     * @return the page
     */
    @GetMapping
    @Operation(summary = "List templates")
    @PreAuthorize("@templatePolicy.canGetAll(authentication, #filter)")
    public ResponseEntity<Paginated<NotificationTemplateResponse>> getAll(
            @Valid @ModelAttribute PaginationFilter filter) {
        return ResponseEntity.ok(service.getAll(filter));
    }

    /**
     * Which wording a send would actually get.
     *
     * <p>Here because the answer is not obvious from the listing: a message asked for in one
     * language can legitimately be sent in another, and this is how "why was this customer written
     * to in English" is answered without reading the fallback rules and guessing.</p>
     *
     * @param templateKey what the message is
     * @param channel the channel it would go on
     * @param locale the recipient's language, or omitted for the default
     * @return the template that would be rendered
     */
    @GetMapping("/resolve")
    @Operation(summary = "Show which template a send would resolve to, after locale fallback")
    @PreAuthorize("@templatePolicy.canResolve(authentication)")
    public ResponseEntity<NotificationTemplateResponse> resolve(
            @RequestParam String templateKey,
            @RequestParam Channel channel,
            @RequestParam(required = false) String locale) {
        return ResponseEntity.ok(service.resolveAsResponse(templateKey, channel, locale));
    }

    /**
     * Changes the wording of a message.
     * @param request the new wording
     * @return it, as stored
     */
    @PutMapping
    @Operation(summary = "Change the wording of a message")
    @PreAuthorize("@templatePolicy.canUpdate(authentication, #request)")
    public ResponseEntity<NotificationTemplateResponse> update(
            @Valid @RequestBody NotificationTemplateUpdateRequest request) {
        return ResponseEntity.ok(service.update(request));
    }

    /**
     * Retires a template, so resolution stops returning it.
     * @param id the 24-character hex string
     * @return the template
     */
    @DeleteMapping("/{id}/disable")
    @Operation(summary = "Retire a template")
    @PreAuthorize("@templatePolicy.canDisable(authentication)")
    public ResponseEntity<NotificationTemplateResponse> disable(@PathVariable String id) {
        return ResponseEntity.ok(service.disable(id));
    }

    /**
     * Soft-deletes a template.
     * @param id the 24-character hex string
     * @return the template
     */
    @DeleteMapping("/{id}")
    @Operation(summary = "Delete a template")
    @PreAuthorize("@templatePolicy.canDelete(authentication, #id)")
    public ResponseEntity<NotificationTemplateResponse> delete(@PathVariable String id) {
        return ResponseEntity.ok(service.delete(id));
    }
}
