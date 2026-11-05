package com.confluo.notifications.service;

import com.confluo.common.dto.Paginated;
import com.confluo.common.exception.ResourceNotFoundException;
import com.confluo.common.filter.PaginationFilter;
import com.confluo.common.jpa.mapper.JpaPaginationMapper;
import com.confluo.common.repository.BaseRepository;
import com.confluo.common.service.BaseService;
import com.confluo.notifications.dto.recipient.RecipientCreateRequest;
import com.confluo.notifications.dto.recipient.RecipientResponse;
import com.confluo.notifications.dto.recipient.RecipientUpdateRequest;
import com.confluo.notifications.entity.Recipient;
import com.confluo.notifications.entity.RecipientSource;
import com.confluo.notifications.mapper.RecipientMapper;
import com.confluo.notifications.repository.jpa.RecipientRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Locale;
import java.util.UUID;

/**
 * Who this service can reach, and how.
 *
 * <p>A recipient can come to exist in four ways, and all four end here so that the normalising and
 * the reachability rule are written once:</p>
 *
 * <ol>
 *   <li>an event from the service that owns users - the intended one, and the only one that stays
 *       current by itself. That service does not exist yet, so nothing calls it;</li>
 *   <li>{@link #resolveOrCreate} with contact details a send request carried, which is a caller's
 *       claim and recorded as such;</li>
 *   <li>{@link #resolveOrCreate} with none, which falls through to
 *       {@link FirebaseRecipientResolver};</li>
 *   <li>{@link #create}, which is an administrator fixing or seeding a row by hand.</li>
 * </ol>
 *
 * <p>Two normalisations happen on every path and are not optional. An address is lowercased,
 * because {@code ck_recipients_email_lowercase} requires it and because the lookups that answer
 * "whose address is this?" are exact so they can use their index. A language code is lowercased for
 * the same reason the platform lowercases its own.</p>
 */
@Service
@RequiredArgsConstructor
@Transactional
@Slf4j
public class RecipientService implements BaseService<Recipient, RecipientCreateRequest, RecipientUpdateRequest, RecipientResponse> {

    private final RecipientRepository repository;
    private final RecipientMapper mapper;
    private final FirebaseRecipientResolver firebaseResolver;

    /**
     * Refuses a uid another recipient already holds, so the caller gets a 422 naming the uid rather
     * than the database's own constraint violation.
     * @param firebaseUid the uid to check
     */
    private void rejectDuplicateUid(String firebaseUid) {
        if (repository.existsByFirebaseUid(firebaseUid)) {
            throw new IllegalStateException("Recipient %s already exists".formatted(firebaseUid));
        }
    }

    /**
     * Refuses a recipient nothing could be sent to.
     *
     * <p>{@code ck_recipients_reachable} would refuse it as well, as a
     * {@code DataIntegrityViolationException} that nothing maps to a sensible status. Checked here
     * so the answer is a 422 that says which fields are the problem.</p>
     *
     * @param email the address, or null
     * @param phoneE164 the number, or null
     */
    private void requireReachable(String email, String phoneE164) {
        if (email == null && phoneE164 == null) {
            throw new IllegalStateException("A recipient needs an email address or a phone number");
        }
    }

    /**
     * Refuses a timezone that is not a zone.
     *
     * <p>Nothing reads it yet, which is exactly why it is validated now: a bad value stored today
     * surfaces the day quiet hours are switched on, as messages held forever or sent at the wrong
     * time, and by then nobody connects the two.</p>
     *
     * @param timezone the IANA zone, or null
     */
    private void requireKnownTimezone(String timezone) {
        if (timezone == null) {
            return;
        }
        try {
            ZoneId.of(timezone);
        } catch (DateTimeException e) {
            throw new IllegalStateException("%s is not an IANA time zone".formatted(timezone));
        }
    }

    /** @param email the address as it arrived, or null @return it lowercased, or null */
    private static String normalizeEmail(String email) {
        return email == null ? null : email.toLowerCase(Locale.ROOT);
    }

    /** @param code the language code as it arrived, or null @return it lowercased, or null */
    private static String normalizeLanguageCode(String code) {
        return code == null ? null : code.toLowerCase(Locale.ROOT);
    }

    @Override
    public BaseRepository<Recipient> getRepository() {
        return repository;
    }

    @Override
    public RecipientResponse create(RecipientCreateRequest request) {
        String email = normalizeEmail(request.email());
        rejectDuplicateUid(request.firebaseUid());
        requireReachable(email, request.phoneE164());
        requireKnownTimezone(request.timezone());

        Recipient recipient = new Recipient();
        mapper.createEntityFromRequest(recipient, request);
        recipient.setEmail(email);
        recipient.setLanguageCode(normalizeLanguageCode(request.languageCode()));
        recipient.setSource(RecipientSource.ADMIN);
        recipient.setLastSyncedAt(Instant.now());

        Recipient saved = repository.save(recipient);
        log.info("Recipient created [id={}, uid={}, source={}]",
                saved.getId(), saved.getFirebaseUid(), saved.getSource());
        return mapper.toResponse(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public Recipient getEntityById(UUID id) {
        return repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Recipient %s not found".formatted(id)));
    }

    /**
     * The recipient for a uid, for the callers that hold one rather than an id.
     * @param firebaseUid the uid
     * @return the recipient
     * @throws ResourceNotFoundException if this service does not know the uid
     */
    @Transactional(readOnly = true)
    public Recipient getEntityByFirebaseUid(String firebaseUid) {
        return repository.findByFirebaseUid(firebaseUid)
                .orElseThrow(() -> new ResourceNotFoundException("Recipient %s not found".formatted(firebaseUid)));
    }

    @Override
    @Transactional(readOnly = true)
    public RecipientResponse getById(UUID id) {
        return mapper.toResponse(getEntityById(id));
    }

    /**
     * A recipient by uid, as the API returns it.
     * @param firebaseUid the uid
     * @return the recipient
     */
    @Transactional(readOnly = true)
    public RecipientResponse getByFirebaseUid(String firebaseUid) {
        return mapper.toResponse(getEntityByFirebaseUid(firebaseUid));
    }

    @Override
    @Transactional(readOnly = true)
    public Paginated<RecipientResponse> getAll(PaginationFilter filter) {
        return JpaPaginationMapper.findAll(repository, filter, mapper::toResponse);
    }

    @Override
    public RecipientResponse update(RecipientUpdateRequest request) {
        Recipient recipient = getEntityById(request.id());
        if (!recipient.getFirebaseUid().equals(request.firebaseUid())) {
            throw new IllegalStateException(
                    "Recipient %s is uid %s and cannot be re-pointed at %s"
                            .formatted(recipient.getId(), recipient.getFirebaseUid(), request.firebaseUid()));
        }

        String email = normalizeEmail(request.email());
        requireReachable(email, request.phoneE164());
        requireKnownTimezone(request.timezone());

        mapper.updateEntityFromRequest(recipient, request);
        recipient.setEmail(email);
        recipient.setLanguageCode(normalizeLanguageCode(request.languageCode()));
        // An edit by hand is now where this row's contents come from, whatever wrote it before.
        recipient.setSource(RecipientSource.ADMIN);
        recipient.setLastSyncedAt(Instant.now());

        Recipient saved = repository.save(recipient);
        log.info("Recipient updated [id={}, uid={}]", saved.getId(), saved.getFirebaseUid());
        return mapper.toResponse(saved);
    }

    @Override
    public RecipientResponse disable(UUID id) {
        Recipient recipient = getEntityById(id);
        if (recipient.getDisabledAt() == null) {
            recipient.setDisabledAt(Instant.now());
            recipient = repository.save(recipient);
            log.info("Recipient disabled [id={}, uid={}]", recipient.getId(), recipient.getFirebaseUid());
        }
        return mapper.toResponse(recipient);
    }

    @Override
    public RecipientResponse delete(UUID id) {
        Recipient recipient = getEntityById(id);
        recipient.setDeletedAt(Instant.now());

        Recipient saved = repository.save(recipient);
        log.info("Recipient deleted [id={}, uid={}]", saved.getId(), saved.getFirebaseUid());
        return mapper.toResponse(saved);
    }

    /**
     * The recipient for a uid, creating one if this service has never heard of them.
     *
     * <p>This is what the pipeline calls, and the order of its two fallbacks is the order of how
     * much they can be trusted. Contact details the caller supplied are used first because they
     * cost nothing and the caller is usually the service that just read them; Firebase is asked
     * only when there are none, because it is a network call on the path of every send.</p>
     *
     * <p><strong>An existing recipient is returned untouched.</strong> The supplied details are not
     * written over it, and that is deliberate: a row an event wrote is more current than a claim
     * carried in whatever asked for this message, and letting the claim win would quietly undo the
     * sync this service depends on. Correcting a known recipient goes through {@link #update}.</p>
     *
     * @param firebaseUid the uid the message is for
     * @param email an address the caller supplied, or null
     * @param phoneE164 a number the caller supplied, or null
     * @param languageCode a language the caller supplied, or null
     * @return the recipient, existing or newly created
     * @throws ResourceNotFoundException if the uid is unknown and nothing could be learned about it
     */
    public Recipient resolveOrCreate(String firebaseUid, String email, String phoneE164, String languageCode) {
        return repository.findByFirebaseUid(firebaseUid)
                .orElseGet(() -> createFromClaimOrFirebase(firebaseUid, email, phoneE164, languageCode));
    }

    private Recipient createFromClaimOrFirebase(
            String firebaseUid, String email, String phoneE164, String languageCode) {
        String normalizedEmail = normalizeEmail(email);

        Recipient recipient;
        if (normalizedEmail != null || phoneE164 != null) {
            recipient = new Recipient();
            recipient.setFirebaseUid(firebaseUid);
            recipient.setEmail(normalizedEmail);
            recipient.setPhoneE164(phoneE164);
            recipient.setLanguageCode(normalizeLanguageCode(languageCode));
            // Neither is claimed as verified: the caller said where to write, not that it is theirs.
            recipient.setSource(RecipientSource.SEND_REQUEST);
            recipient.setLastSyncedAt(Instant.now());
        } else {
            recipient = firebaseResolver.lookUp(firebaseUid)
                    .orElseThrow(() -> new ResourceNotFoundException(
                            "Nothing is known about recipient %s, and no contact details were given"
                                    .formatted(firebaseUid)));
        }

        Recipient saved = repository.save(recipient);
        log.info("Recipient created [id={}, uid={}, source={}]",
                saved.getId(), saved.getFirebaseUid(), saved.getSource());
        return saved;
    }
}
