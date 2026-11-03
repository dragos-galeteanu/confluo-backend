package com.confluo.notifications.controller;

import com.confluo.notifications.dto.preference.NotificationPreferenceResponse;
import com.confluo.notifications.dto.preference.NotificationPreferencesUpdateRequest;
import com.confluo.notifications.service.NotificationPreferenceService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * What a recipient has agreed to receive.
 *
 * <p>Not a {@link com.confluo.common.controller.BaseController}, and not because preferences are
 * unusual: that base addresses a resource by a {@code UUID} of its own, and these are addressed by
 * the recipient's Firebase uid - the name an administrator looking into a complaint actually has.
 * A preference row's own id is of no interest to anyone outside this service.</p>
 *
 * <p>There is no create and no delete. A listing always returns every channel and category, with
 * the ones nobody has answered shown at their default, so there is nothing to create; and removing
 * a preference is setting it back to that default, which is a {@code PUT} like any other change.</p>
 */
@RestController
@RequestMapping("/api/notifications/recipients/{firebaseUid}/preferences")
@RequiredArgsConstructor
@Tag(name = "Notification preferences")
public class NotificationPreferenceController {

    private final NotificationPreferenceService service;

    /**
     * Everything a recipient will and will not receive.
     *
     * <p>Every combination of channel and category, with {@code stored} saying which are the
     * recipient's own answers and which are the defaults filled in.</p>
     *
     * @param firebaseUid the recipient
     * @return one entry per channel and category
     */
    @GetMapping
    @Operation(summary = "Get a recipient's notification preferences, defaults included")
    @PreAuthorize("@preferencePolicy.canList(authentication, #firebaseUid)")
    public ResponseEntity<List<NotificationPreferenceResponse>> list(@PathVariable String firebaseUid) {
        return ResponseEntity.ok(service.listFor(firebaseUid));
    }

    /**
     * Changes some of a recipient's answers, leaving the rest alone.
     *
     * <p>All of the settings or none. Switching a transactional category off is refused with a 422:
     * a password reset is part of an action somebody is in the middle of, and a preference that
     * could stop it would be a preference to break the account.</p>
     *
     * @param firebaseUid the recipient
     * @param request the settings to apply
     * @return the recipient's whole set afterwards
     */
    @PutMapping
    @Operation(summary = "Set some of a recipient's notification preferences")
    @PreAuthorize("@preferencePolicy.canApply(authentication, #firebaseUid, #request)")
    public ResponseEntity<List<NotificationPreferenceResponse>> apply(
            @PathVariable String firebaseUid,
            @Valid @RequestBody NotificationPreferencesUpdateRequest request) {
        return ResponseEntity.ok(service.apply(firebaseUid, request));
    }
}
