package com.confluo.notifications.service;

import com.confluo.common.dto.Paginated;
import com.confluo.common.exception.ResourceNotFoundException;
import com.confluo.common.filter.PaginationFilter;
import com.confluo.common.jpa.mapper.JpaPaginationMapper;
import com.confluo.notifications.channel.Channel;
import com.confluo.notifications.document.NotificationTemplate;
import com.confluo.notifications.document.NotificationRenderedMessage;
import com.confluo.notifications.dto.notification.NotificationResponse;
import com.confluo.notifications.dto.notification.NotificationSendRequest;
import com.confluo.notifications.dto.notification.NotificationRenderedMessageResponse;
import com.confluo.notifications.entity.Notification;
import com.confluo.notifications.entity.NotificationStatus;
import com.confluo.notifications.entity.Recipient;
import com.confluo.notifications.mapper.NotificationMapper;
import com.confluo.notifications.repository.jpa.NotificationRepository;
import com.confluo.notifications.repository.mongo.NotificationRenderedMessageRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Whether a message should exist, and if so, what it says.
 *
 * <p>This is the pipeline. Everything before the insert is a question that can stop a send, asked
 * in order of how cheap it is and how final the answer is:</p>
 *
 * <ol>
 *   <li><strong>Who is it for?</strong> A named send resolves a recipient, creating one from the
 *       contact details supplied or from Firebase. An ad-hoc send names a destination and nobody.</li>
 *   <li><strong>Where would it go?</strong> The address or number for that channel. A recipient with
 *       no phone number cannot be sent a text message, and that is the caller's mistake rather than
 *       a message that waits forever.</li>
 *   <li><strong>Is the destination suppressed?</strong> Final and not overridable. A bounce or a
 *       STOP outranks everything, including the caller's intent.</li>
 *   <li><strong>Does their preference allow it?</strong> Consulted only for a named send, and never
 *       able to stop a transactional message.</li>
 *   <li><strong>Has it been sent before?</strong> By deduplication key, if one was given.</li>
 *   <li><strong>What does it say?</strong> Resolve the template, render it, freeze the words.</li>
 * </ol>
 *
 * <p>A message stopped by suppression or a preference is <strong>recorded, not dropped</strong>, as
 * a {@code SUPPRESSED} row. "We decided not to send this, and here is which check said so" is an
 * answer; silence is not, and silence is what every support conversation about a missing message
 * starts from.</p>
 *
 * <p><strong>Nothing is sent from here.</strong> The row commits as {@code PENDING} and the
 * dispatcher picks it up afterwards - the same arrangement as the outbox, for the same reason:
 * nothing may reach a provider before the decision to send it has committed. Until the dispatcher
 * exists, a {@code PENDING} row is simply where a message stops.</p>
 */
@Service
@RequiredArgsConstructor
@Transactional
@Slf4j
public class NotificationService {

    private final NotificationRepository repository;
    private final NotificationRenderedMessageRepository renderedMessages;
    private final NotificationMapper mapper;
    private final RecipientService recipients;
    private final NotificationPreferenceService preferences;
    private final SuppressionService suppressions;
    private final NotificationTemplateService templates;
    private final NotificationRenderingService rendering;

    /**
     * Decides whether a message should exist and, if so, writes it.
     *
     * @param request what to send and to whom
     * @param requestedBy the Firebase uid or service name behind the request
     * @param triggerEventId the event that asked for this, or null if an API call did
     * @return the message, which may already be {@code SUPPRESSED}
     */
    public NotificationResponse send(NotificationSendRequest request, String requestedBy, UUID triggerEventId) {
        requireExactlyOneTarget(request);

        // First, before anything with a side effect. Resolving a recipient can create one, and
        // creating a row for somebody because a caller retried a request we already answered is
        // not a duplicate message but it is still a duplicate.
        Optional<Notification> alreadySent = findByDedupKey(request.dedupKey());
        if (alreadySent.isPresent()) {
            // Returned rather than refused, which makes the route idempotent on the key: a caller
            // that retries because it did not see our response gets the same message back instead
            // of a 422 it has no way to interpret.
            Notification existing = alreadySent.get();
            log.info("Message already exists for dedupKey {} [id={}, status={}]",
                    request.dedupKey(), existing.getId(), existing.getStatus());
            return mapper.toResponse(existing);
        }

        Recipient recipient = request.firebaseUid() == null ? null
                : recipients.resolveOrCreate(
                        request.firebaseUid(), request.email(), request.phoneE164(), request.languageCode());

        String destination = destinationFor(request, recipient);
        NotificationTemplate template = templates.resolve(
                request.templateKey(), request.channel(), localeFor(request, recipient));

        Optional<NotificationStatus> refusal =
                refuse(request.channel(), template, destination, recipient);
        if (refusal.isPresent()) {
            return mapper.toResponse(record(
                    request, template, destination, recipient, refusal.get(),
                    requestedBy, triggerEventId));
        }

        Notification notification = record(
                request, template, destination, recipient, NotificationStatus.PENDING,
                requestedBy, triggerEventId);
        freeze(notification, template, variablesOf(request));

        log.info("Message queued [id={}, channel={}, template={}, locale={}]",
                notification.getId(), notification.getChannel(),
                notification.getTemplateKey(), notification.getLocale());
        return mapper.toResponse(notification);
    }

    /**
     * One message and where it has got to.
     * @param id the message
     * @return it, as the API returns it
     */
    @Transactional(readOnly = true)
    public NotificationResponse getById(UUID id) {
        return mapper.toResponse(getEntityById(id));
    }

    /**
     * One message, as an entity.
     * @param id the message
     * @return it
     */
    @Transactional(readOnly = true)
    public Notification getEntityById(UUID id) {
        return repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Notification %s not found".formatted(id)));
    }

    /**
     * The words a message was sent with.
     * @param id the message
     * @return its frozen content
     */
    @Transactional(readOnly = true)
    public NotificationRenderedMessageResponse getContent(UUID id) {
        // Checked first, so a message that does not exist is a 404 about the message rather than
        // about its content - and so that an id nobody has sent cannot be probed for either.
        getEntityById(id);
        NotificationRenderedMessage rendered = renderedMessages.findByNotificationId(id)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Notification %s has no rendered content".formatted(id)));
        return new NotificationRenderedMessageResponse(
                rendered.getNotificationId(),
                rendered.getSubject(),
                rendered.getTextBody(),
                rendered.getHtmlBody(),
                rendered.getVariables(),
                rendered.getTemplateKey(),
                rendered.getTemplateVersion(),
                rendered.getLocale(),
                rendered.getCreatedAt());
    }

    /**
     * The delivery log.
     * @param filter pagination, filter, and sort parameters
     * @return the page
     */
    @Transactional(readOnly = true)
    public Paginated<NotificationResponse> getAll(PaginationFilter filter) {
        return JpaPaginationMapper.findAll(repository, filter, mapper::toResponse);
    }

    /**
     * Refuses a request whose target cannot be worked out.
     *
     * <p>Both or neither, rather than a precedence rule. A request carrying a uid and a destination
     * is a caller who believes one of them is being used and may well be wrong about which; picking
     * one silently would send somebody else's message to an address nobody checked.</p>
     *
     * @param request the request
     */
    private static void requireExactlyOneTarget(NotificationSendRequest request) {
        boolean named = request.firebaseUid() != null && !request.firebaseUid().isBlank();
        boolean adHoc = request.destination() != null && !request.destination().isBlank();

        if (named == adHoc) {
            throw new IllegalStateException(
                    "A message needs either a firebaseUid or a destination, and not both");
        }
    }

    /**
     * Where the message would go.
     *
     * @param request the request
     * @param recipient the resolved recipient, or null for an ad-hoc send
     * @return the address or number, lowercased so it matches the suppression list
     */
    private static String destinationFor(NotificationSendRequest request, Recipient recipient) {
        if (recipient == null) {
            return SuppressionService.normalize(request.destination());
        }
        String destination = switch (request.channel()) {
            case EMAIL -> recipient.getEmail();
            case SMS -> recipient.getPhoneE164();
            case IN_APP, PUSH -> throw new IllegalStateException(
                    "%s messages cannot be sent yet".formatted(request.channel()));
        };
        if (destination == null) {
            throw new IllegalStateException(
                    "Recipient %s has no destination for %s"
                            .formatted(recipient.getFirebaseUid(), request.channel()));
        }
        return SuppressionService.normalize(destination);
    }

    /**
     * Which language to render in.
     *
     * <p>The request wins over the recipient, because an override is only ever passed by a caller
     * who knows something this service does not - the language of the page somebody is on, say,
     * rather than the one on their profile.</p>
     *
     * @param request the request
     * @param recipient the recipient, or null
     * @return the locale to resolve with, which may be null for the default
     */
    private static String localeFor(NotificationSendRequest request, Recipient recipient) {
        if (request.locale() != null && !request.locale().isBlank()) {
            return request.locale();
        }
        return recipient == null ? null : recipient.getLanguageCode();
    }

    /**
     * The check, if any, that stops this message.
     *
     * @param channel the channel
     * @param template the resolved template, which declares the category
     * @param destination where it would go
     * @param recipient the recipient, or null for an ad-hoc send
     * @return the status to record instead of sending, or empty to go ahead
     */
    private Optional<NotificationStatus> refuse(
            Channel channel, NotificationTemplate template, String destination, Recipient recipient) {
        if (suppressions.isSuppressed(channel, destination)) {
            log.info("Message suppressed: destination is on the suppression list [channel={}, template={}]",
                    channel, template.getTemplateKey());
            return Optional.of(NotificationStatus.SUPPRESSED);
        }
        // An ad-hoc send has nobody whose preferences these would be, so there is nothing to ask.
        // Stated here rather than left implicit, because it is a hole and the route is narrow
        // because of it.
        if (recipient != null && !preferences.isAllowed(recipient, channel, template.getCategory())) {
            log.info("Message suppressed: preference says no [uid={}, channel={}, category={}]",
                    recipient.getFirebaseUid(), channel, template.getCategory());
            return Optional.of(NotificationStatus.SUPPRESSED);
        }
        return Optional.empty();
    }

    /**
     * The message already sent under a deduplication key, if there is one.
     *
     * <p>The unique partial index is what actually decides; this is the cheap look before the
     * insert, so the usual case costs a query rather than a caught constraint violation.</p>
     *
     * @param dedupKey the key, or null for a repeatable send
     * @return the existing message, or empty
     */
    private Optional<Notification> findByDedupKey(String dedupKey) {
        if (dedupKey == null || dedupKey.isBlank()) {
            return Optional.empty();
        }
        return repository.findByDedupKey(dedupKey);
    }

    /**
     * Writes the row.
     *
     * @param request the request
     * @param template the resolved template
     * @param destination where it goes
     * @param recipient the recipient, or null
     * @param status {@code PENDING}, or the status of whatever stopped it
     * @param requestedBy who asked
     * @param triggerEventId the event that asked, or null
     * @return the saved notification
     */
    private Notification record(
            NotificationSendRequest request, NotificationTemplate template, String destination,
            Recipient recipient, NotificationStatus status, String requestedBy, UUID triggerEventId) {
        Notification notification = new Notification();
        notification.setRecipient(recipient);
        notification.setChannel(request.channel());
        // From the template, never from the request: a caller who could name the category could
        // call their marketing transactional and make it undeclinable.
        notification.setCategory(template.getCategory());
        notification.setTemplateKey(template.getTemplateKey());
        notification.setLocale(template.getLocale());
        notification.setDestination(destination);
        notification.setStatus(status);
        notification.setNextAttemptAt(Instant.now());
        notification.setDedupKey(emptyToNull(request.dedupKey()));
        notification.setCorrelationId(emptyToNull(request.correlationId()));
        notification.setTriggerEventId(triggerEventId);
        notification.setRequestedBy(requestedBy);

        return repository.save(notification);
    }

    /**
     * Renders the message and writes its words down, before the row can be claimed.
     *
     * <p>The order is the point. The content is written while the transaction is still open, so a
     * row that commits as {@code PENDING} always has words to send; a render that fails rolls the
     * row back with it. The reverse would leave the dispatcher able to claim a message nobody ever
     * wrote.</p>
     *
     * <p>MongoDB is not in the transaction, so the one thing that can be left behind is a rendered
     * message whose notification was rolled back. That is harmless - nothing reads this collection
     * except by the id of a notification that exists - and it is the right way round: an orphaned
     * document costs disk, while an unrendered {@code PENDING} row would cost a message.</p>
     *
     * @param notification the saved row
     * @param template the template to render
     * @param variables what to fill it in with
     */
    private void freeze(Notification notification, NotificationTemplate template, Map<String, Object> variables) {
        NotificationRenderedContent content = rendering.render(template, variables);

        NotificationRenderedMessage rendered = new NotificationRenderedMessage();
        rendered.setNotificationId(notification.getId());
        rendered.setSubject(content.subject());
        rendered.setTextBody(content.textBody());
        rendered.setHtmlBody(content.htmlBody());
        rendered.setVariables(variables);
        rendered.setTemplateKey(template.getTemplateKey());
        rendered.setTemplateVersion(template.getVersion());
        rendered.setLocale(template.getLocale());

        renderedMessages.save(rendered);
    }

    /** @param request the request @return its variables, never null */
    private static Map<String, Object> variablesOf(NotificationSendRequest request) {
        return request.variables() == null ? new HashMap<>() : new HashMap<>(request.variables());
    }

    /** @param value a string from a request @return it, or null if it was blank */
    private static String emptyToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
