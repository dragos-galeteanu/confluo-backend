package com.confluo.notifications.controller;

import com.confluo.common.dto.Paginated;
import com.confluo.common.filter.PaginationFilter;
import com.confluo.notifications.dto.notification.NotificationResponse;
import com.confluo.notifications.dto.notification.NotificationSendRequest;
import com.confluo.notifications.dto.notification.RenderedContentResponse;
import com.confluo.notifications.service.NotificationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Asking for a message to be sent, and reading what was.
 *
 * <p>Written out rather than inherited from {@link com.confluo.common.controller.BaseController},
 * and for a different reason than the template controller's. The identifier is a {@code UUID} and
 * would fit perfectly well; what does not fit is the rest of the contract. {@code BaseService}
 * promises an update, a disable and a delete for every resource, and a message that has been
 * decided on is a record of a decision - there is nothing to edit and nothing to turn off. The
 * three routes here are the three that mean something, and a caller is told so by their absence
 * rather than by a 405 from a route that exists.</p>
 *
 * <p>A cancel and a resend both belong here eventually. Neither can be written yet: cancelling
 * means taking a row out from under the dispatcher, and resending means handing it back - and the
 * dispatcher does not exist.</p>
 */
@RestController
@RequestMapping("/api/notifications")
@RequiredArgsConstructor
@Tag(name = "Notifications")
public class NotificationController {

    private final NotificationService service;

    /**
     * Asks for a message to be sent.
     *
     * <p>Answers {@code 201} whether or not the message will actually go: a request stopped by the
     * suppression list or by somebody's preferences is still a request that was accepted and
     * recorded, and its {@code status} says what happened to it. A caller that wants to know
     * whether a message was <em>sent</em> is asking a question only the dispatcher can answer, and
     * asking it synchronously would mean waiting on a mail server.</p>
     *
     * <p>Idempotent on {@code dedupKey}: a retry returns the message that already exists.</p>
     *
     * @param request what to send and to whom
     * @param authentication who is asking, recorded on the message as {@code requestedBy}
     * @return the message, with the status the pipeline decided
     */
    @PostMapping
    @Operation(summary = "Ask for a message to be sent")
    @PreAuthorize("@notificationPolicy.canCreate(authentication, #request)")
    public ResponseEntity<NotificationResponse> send(
            @Valid @RequestBody NotificationSendRequest request, Authentication authentication) {
        // The name and not SecurityUtils.extractUserId, which returns a UUID: requestedBy holds a
        // Firebase uid - or, once service-to-service callers can reach this, a service's name.
        NotificationResponse response = service.send(request, authentication.getName(), null);
        return new ResponseEntity<>(response, HttpStatus.CREATED);
    }

    /**
     * One message and where it has got to.
     * @param id the message
     * @return it
     */
    @GetMapping("/{id}")
    @Operation(summary = "Get a message and its status")
    @PreAuthorize("@notificationPolicy.canGetById(authentication, #id)")
    public ResponseEntity<NotificationResponse> getById(@PathVariable UUID id) {
        return ResponseEntity.ok(service.getById(id));
    }

    /**
     * The words a message was sent with.
     *
     * <p>A route of its own rather than a field on the message, because this is the one thing here
     * that is kilobytes rather than bytes - and because a delivery log is read to find out whether
     * something went, not to read it.</p>
     *
     * @param id the message
     * @return its frozen content
     */
    @GetMapping("/{id}/content")
    @Operation(summary = "Get the words a message was sent with")
    @PreAuthorize("@notificationPolicy.canReadContent(authentication, #id)")
    public ResponseEntity<RenderedContentResponse> getContent(@PathVariable UUID id) {
        return ResponseEntity.ok(service.getContent(id));
    }

    /**
     * The delivery log.
     * @param filter pagination, filter, and sort parameters
     * @return the page
     */
    @GetMapping
    @Operation(summary = "List messages")
    @PreAuthorize("@notificationPolicy.canGetAll(authentication, #filter)")
    public ResponseEntity<Paginated<NotificationResponse>> getAll(
            @Valid @ModelAttribute PaginationFilter filter) {
        return ResponseEntity.ok(service.getAll(filter));
    }
}
