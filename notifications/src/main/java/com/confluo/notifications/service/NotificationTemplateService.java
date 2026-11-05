package com.confluo.notifications.service;

import com.confluo.common.dto.Paginated;
import com.confluo.common.exception.ResourceNotFoundException;
import com.confluo.common.filter.PaginationFilter;
import com.confluo.common.mongo.mapper.MongoPaginationMapper;
import com.confluo.notifications.channel.Channel;
import com.confluo.notifications.config.NotificationProperties;
import com.confluo.notifications.document.NotificationTemplate;
import com.confluo.notifications.dto.template.NotificationTemplateCreateRequest;
import com.confluo.notifications.dto.template.NotificationTemplateResponse;
import com.confluo.notifications.dto.template.NotificationTemplateUpdateRequest;
import com.confluo.notifications.mapper.NotificationTemplateMapper;
import com.confluo.notifications.repository.mongo.NotificationTemplateRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bson.types.ObjectId;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * The wording of every message, and which wording a particular send gets.
 *
 * <p><strong>Not a {@code BaseService}.</strong> That interface - and the {@code BaseController}
 * above it - reaches a resource through a {@code UUID}-keyed repository, and a document is keyed by
 * an {@code ObjectId}; {@code BaseEntity}'s own documentation explains why the two stacks are kept
 * apart. So the methods here are the same six by hand, over {@code MongoPaginationMapper} for the
 * listing, and the controller is written out rather than inherited.</p>
 *
 * <p>Resolution is the interesting part. A message asked for in {@code ro-RO} should be sent in
 * Romanian if that exists, in the regionless Romanian if only that does, and in the default
 * language rather than not at all - so a lookup walks a chain and takes the first live template it
 * finds. When it does not get the locale it was asked for it says so in the log, which is how a
 * missing translation is noticed before somebody reports being written to in English.</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class NotificationTemplateService {

    private final NotificationTemplateRepository repository;
    private final NotificationTemplateMapper mapper;
    private final MongoTemplate mongoTemplate;
    private final NotificationProperties properties;

    /**
     * The locales to try, in order, for a requested one.
     *
     * <p>{@code ro-RO} gives {@code ro-ro}, {@code ro}, then the default; {@code ro} gives
     * {@code ro} and the default; nothing gives the default alone. The chain is deduplicated, so
     * asking for the default language does not look it up twice, and underscores are accepted
     * because {@code Locale.toString()} produces them and callers pass what they have.</p>
     *
     * @param requested the locale asked for, or null
     * @return the candidates to try, never empty
     */
    public List<String> localeChain(String requested) {
        LinkedHashSet<String> chain = new LinkedHashSet<>();
        if (requested != null && !requested.isBlank()) {
            String normalized = normalizeLocale(requested);
            chain.add(normalized);
            int separator = normalized.indexOf('-');
            if (separator > 0) {
                chain.add(normalized.substring(0, separator));
            }
        }
        chain.add(normalizeLocale(properties.defaultLocale()));
        return List.copyOf(chain);
    }

    /**
     * The wording a send should use.
     *
     * @param templateKey what the message is
     * @param channel the channel it goes on
     * @param requestedLocale the recipient's language, or null for the default
     * @return the live template to render
     * @throws ResourceNotFoundException if nothing is written for this message on this channel, in
     *         any language on the chain - including the default, which is the real failure: it
     *         means a message type exists in code with no wording anywhere
     */
    public NotificationTemplate resolve(String templateKey, Channel channel, String requestedLocale) {
        List<String> chain = localeChain(requestedLocale);
        for (String candidate : chain) {
            Optional<NotificationTemplate> found = repository
                    .findByTemplateKeyAndChannelAndLocaleAndDisabledAtIsNullAndDeletedAtIsNull(
                            templateKey, channel, candidate);
            if (found.isPresent()) {
                if (!candidate.equals(chain.get(0))) {
                    log.warn("No {} wording for {}/{}; falling back to {}",
                            chain.get(0), templateKey, channel, candidate);
                }
                return found.get();
            }
        }
        throw new ResourceNotFoundException(
                "No template for %s on %s in any of %s".formatted(templateKey, channel, chain));
    }

    /**
     * The wording a send would use, as the API returns it.
     *
     * <p>The same resolution the pipeline runs, so an administrator asking "which template would
     * this get" is told by the code that decides it rather than by a second implementation of the
     * fallback rules.</p>
     *
     * @param templateKey what the message is
     * @param channel the channel it would go on
     * @param requestedLocale the recipient's language, or null for the default
     * @return the template that would be rendered
     */
    public NotificationTemplateResponse resolveAsResponse(
            String templateKey, Channel channel, String requestedLocale) {
        return mapper.toResponse(resolve(templateKey, channel, requestedLocale));
    }

    /**
     * Adds the wording of a message.
     * @param request the wording
     * @return it, as the API returns it
     */
    public NotificationTemplateResponse create(NotificationTemplateCreateRequest request) {
        String locale = normalizeLocale(request.locale());
        requireFieldsThatSuitTheChannel(
                request.channel(), request.subject(), request.htmlBody());
        requireUnescapedInlineSyntaxInTextFields(request.subject(), request.textBody());
        rejectDuplicateScope(request.templateKey(), request.channel(), locale);

        NotificationTemplate template = new NotificationTemplate();
        mapper.createEntityFromRequest(template, request);
        template.setLocale(locale);
        template.setVariables(normalizeVariables(request.variables()));
        template.setVersion(1);
        warnAboutVariablesNoBodyUses(template);
        warnAboutUnescapedHtml(template);

        NotificationTemplate saved = repository.save(template);
        log.info("Template created [key={}, channel={}, locale={}, id={}]",
                saved.getTemplateKey(), saved.getChannel(), saved.getLocale(), saved.getId());
        return mapper.toResponse(saved);
    }

    /**
     * The template with an id.
     * @param id the 24-character hex string
     * @return the template
     * @throws ResourceNotFoundException if there is no such template, a string that is not an
     *         identifier at all included - there is no template with that id either way
     */
    public NotificationTemplate getEntityById(String id) {
        if (!ObjectId.isValid(id)) {
            throw new ResourceNotFoundException("%s is not a template id".formatted(id));
        }
        return repository.findById(new ObjectId(id))
                .orElseThrow(() -> new ResourceNotFoundException("Template %s not found".formatted(id)));
    }

    /**
     * A template, as the API returns it.
     * @param id the 24-character hex string
     * @return the template
     */
    public NotificationTemplateResponse getById(String id) {
        return mapper.toResponse(getEntityById(id));
    }

    /**
     * Every template matching a filter.
     * @param filter pagination, filter, and sort parameters
     * @return the page
     */
    public Paginated<NotificationTemplateResponse> getAll(PaginationFilter filter) {
        return MongoPaginationMapper.findAll(
                mongoTemplate, NotificationTemplate.class, filter, mapper::toResponse);
    }

    /**
     * Changes the wording of a message, leaving what identifies it alone.
     * @param request the new wording
     * @return it, as the API returns it
     */
    public NotificationTemplateResponse update(NotificationTemplateUpdateRequest request) {
        NotificationTemplate template = getEntityById(request.id());
        requireSameScope(template, request);
        requireFieldsThatSuitTheChannel(
                template.getChannel(), request.subject(), request.htmlBody());
        requireUnescapedInlineSyntaxInTextFields(request.subject(), request.textBody());

        mapper.updateEntityFromRequest(template, request);
        template.setVariables(normalizeVariables(request.variables()));
        // Every edit is a revision, and every message rendered afterwards records which one it was.
        template.setVersion(template.getVersion() + 1);
        warnAboutVariablesNoBodyUses(template);
        warnAboutUnescapedHtml(template);

        NotificationTemplate saved = repository.save(template);
        log.info("Template updated [key={}, channel={}, locale={}, version={}]",
                saved.getTemplateKey(), saved.getChannel(), saved.getLocale(), saved.getVersion());
        return mapper.toResponse(saved);
    }

    /**
     * Retires a template, so resolution stops returning it.
     *
     * <p>Not the same as deleting it: the wording stays readable, the act is dated, and the place
     * it occupies in the unique index stays taken - so nothing can be created over it by accident.
     * A message type whose only wording is retired fails to resolve, which is the intended
     * outcome.</p>
     *
     * @param id the 24-character hex string
     * @return the template
     */
    public NotificationTemplateResponse disable(String id) {
        NotificationTemplate template = getEntityById(id);
        if (template.getDisabledAt() == null) {
            template.setDisabledAt(Instant.now());
            template = repository.save(template);
            log.info("Template retired [key={}, channel={}, locale={}]",
                    template.getTemplateKey(), template.getChannel(), template.getLocale());
        }
        return mapper.toResponse(template);
    }

    /**
     * Soft-deletes a template, removing it from every listing.
     * @param id the 24-character hex string
     * @return the template
     */
    public NotificationTemplateResponse delete(String id) {
        NotificationTemplate template = getEntityById(id);
        template.setDeletedAt(Instant.now());

        NotificationTemplate saved = repository.save(template);
        log.info("Template deleted [key={}, channel={}, locale={}]",
                saved.getTemplateKey(), saved.getChannel(), saved.getLocale());
        return mapper.toResponse(saved);
    }

    /** @param locale the tag as it arrived @return it lowercased, with underscores as hyphens */
    private static String normalizeLocale(String locale) {
        return locale.toLowerCase(Locale.ROOT).replace('_', '-');
    }

    /**
     * @param variables the declared names as they arrived
     * @return them trimmed, without blanks, and without repeats, in the order given
     */
    private static List<String> normalizeVariables(List<String> variables) {
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String variable : variables) {
            String trimmed = variable.trim();
            if (!trimmed.isEmpty()) {
                normalized.add(trimmed);
            }
        }
        return new ArrayList<>(normalized);
    }

    /**
     * Refuses a plain-text field written with Thymeleaf's escaping inline syntax.
     *
     * <p>This is the sharpest edge in the whole templating arrangement, and it is invisible.
     * {@code [[${name}]]} is <em>escaped</em> inline output and {@code [(${name})]} is unescaped -
     * and the escaping is HTML escaping whatever the template mode is. So a text message written
     * with the first form delivers {@code Ben &amp;amp; Jerry} to somebody's handset, and it does
     * it only for the names that happen to contain an ampersand, an angle bracket or an
     * apostrophe - which is to say, it passes every test written with a tidy name in it and fails
     * in production on somebody called O'Brien.</p>
     *
     * <p>There is no reason to want HTML escaping in a plain-text body, so this is refused rather
     * than warned about: a template is written once and read by thousands of people, and the author
     * finding out now is the cheapest possible moment.</p>
     *
     * @param subject the subject given, or null
     * @param textBody the text body given
     */
    private static void requireUnescapedInlineSyntaxInTextFields(String subject, String textBody) {
        for (String field : new String[] {subject, textBody}) {
            if (field != null && field.contains("[[${")) {
                throw new IllegalStateException(
                        "A plain-text field must use the unescaped [(${...})] inline syntax, "
                                + "not [[${...}]], which HTML-escapes its value even in a text message");
            }
        }
    }

    /**
     * Logs an HTML body that bypasses escaping.
     *
     * <p>The mirror image of the rule above, and a warning rather than a refusal: {@code [(${...})]}
     * in an HTML body is sometimes exactly what an author wants, for a block of markup assembled
     * elsewhere. It is also how a variable holding somebody's name becomes an injection into every
     * inbox the message reaches, so it should never be there by accident.</p>
     *
     * @param template the template being saved
     */
    private static void warnAboutUnescapedHtml(NotificationTemplate template) {
        if (template.getHtmlBody() != null && template.getHtmlBody().contains("[(${")) {
            log.warn("Template {}/{}/{} writes unescaped output into its HTML body; a variable "
                            + "holding user input would not be escaped",
                    template.getTemplateKey(), template.getChannel(), template.getLocale());
        }
    }

    /**
     * Refuses the combinations of channel and fields that cannot mean anything.
     *
     * @param channel the channel the wording is for
     * @param subject the subject given, or null
     * @param htmlBody the HTML body given, or null
     */
    private static void requireFieldsThatSuitTheChannel(Channel channel, String subject, String htmlBody) {
        boolean hasSubject = subject != null && !subject.isBlank();
        boolean hasHtml = htmlBody != null && !htmlBody.isBlank();

        if (channel == Channel.EMAIL && !hasSubject) {
            throw new IllegalStateException("An email template needs a subject");
        }
        if (channel != Channel.EMAIL && hasSubject) {
            throw new IllegalStateException("A %s template has no subject".formatted(channel));
        }
        if (channel != Channel.EMAIL && hasHtml) {
            throw new IllegalStateException("A %s template has no HTML body".formatted(channel));
        }
    }

    /**
     * Refuses a second template for a key, channel and locale that already has one.
     *
     * <p>MongoDB's compound unique index would refuse it too, as a write error nothing maps to a
     * sensible status. Checked here so the answer says which three fields collided - and the check
     * ignores whether the existing one is retired, because a retired template still holds its
     * place in that index.</p>
     *
     * @param templateKey what the message is
     * @param channel the channel
     * @param locale the normalised locale
     */
    private void rejectDuplicateScope(String templateKey, Channel channel, String locale) {
        if (repository.findByTemplateKeyAndChannelAndLocaleAndDeletedAtIsNull(templateKey, channel, locale)
                .isPresent()) {
            throw new IllegalStateException(
                    "A %s template for %s in %s already exists".formatted(channel, templateKey, locale));
        }
    }

    /**
     * Refuses an edit that would move a template rather than change it.
     * @param template the stored template
     * @param request what was sent
     */
    private static void requireSameScope(
            NotificationTemplate template, NotificationTemplateUpdateRequest request) {
        if (!template.getTemplateKey().equals(request.templateKey())
                || template.getChannel() != request.channel()
                || !template.getLocale().equals(normalizeLocale(request.locale()))) {
            throw new IllegalStateException(
                    "Template %s is %s/%s/%s and cannot be moved to %s/%s/%s".formatted(
                            template.getId(),
                            template.getTemplateKey(), template.getChannel(), template.getLocale(),
                            request.templateKey(), request.channel(), normalizeLocale(request.locale())));
        }
    }

    /**
     * Logs a declared variable that no body appears to use.
     *
     * <p>A warning and not a refusal, on purpose. The check is a substring one - whether
     * {@code ${name}} occurs anywhere - and an expression written with spaces inside the braces
     * would not match it. Catching the common typo is worth a log line; rejecting a template
     * because of how its author spaced an expression is not.</p>
     *
     * @param template the template being saved
     */
    private static void warnAboutVariablesNoBodyUses(NotificationTemplate template) {
        String everything = String.join("\n",
                Optional.ofNullable(template.getSubject()).orElse(""),
                Optional.ofNullable(template.getTextBody()).orElse(""),
                Optional.ofNullable(template.getHtmlBody()).orElse(""));
        for (String variable : template.getVariables()) {
            if (!everything.contains("${" + variable)) {
                log.warn("Template {}/{}/{} declares {} and appears not to use it",
                        template.getTemplateKey(), template.getChannel(), template.getLocale(), variable);
            }
        }
    }
}
