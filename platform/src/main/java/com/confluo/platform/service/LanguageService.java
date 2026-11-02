package com.confluo.platform.service;

import com.confluo.amqp.event.platform.LanguageDisabledEvent;
import com.confluo.amqp.publish.EventPublisher;
import com.confluo.common.dto.Paginated;
import com.confluo.common.exception.ResourceNotFoundException;
import com.confluo.common.filter.PaginationFilter;
import com.confluo.common.jpa.mapper.JpaPaginationMapper;
import com.confluo.common.repository.BaseRepository;
import com.confluo.common.service.BaseService;
import com.confluo.platform.dto.language.LanguageCreateRequest;
import com.confluo.platform.dto.language.LanguageResponse;
import com.confluo.platform.dto.language.LanguageUpdateRequest;
import com.confluo.platform.entity.Language;
import com.confluo.platform.mapper.LanguageMapper;
import com.confluo.platform.repository.jpa.LanguageRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

/**
 * Languages the platform can be read in.
 */
@Service
@RequiredArgsConstructor
@Transactional
@Slf4j
public class LanguageService implements BaseService<Language, LanguageCreateRequest, LanguageUpdateRequest, LanguageResponse> {

    private final LanguageRepository repository;
    private final LanguageMapper mapper;
    private final EventPublisher eventPublisher;

    /**
     * Refuses a code another language already holds, so the caller gets a 422 naming the code rather
     * than the database's own constraint violation. On an update the language being changed is
     * excluded, or keeping its own code would look like a collision with itself.
     * @param code the code to check
     * @param excludedId the language being updated, or null when creating one
     */
    private void rejectDuplicateCode(String code, UUID excludedId) {
        boolean taken = excludedId == null
                ? repository.existsByCodeIgnoreCase(code)
                : repository.existsByCodeIgnoreCaseAndIdNot(code, excludedId);
        if (taken) {
            throw new IllegalStateException("Language %s already exists".formatted(code));
        }
    }

    @Override
    public BaseRepository<Language> getRepository() {
        return repository;
    }

    @Override
    public LanguageResponse create(LanguageCreateRequest request) {
        String code = request.code().toLowerCase(Locale.ROOT);
        rejectDuplicateCode(code, null);

        Language language = new Language();
        mapper.createEntityFromRequest(language, request);
        language.setCode(code);

        Language saved = repository.save(language);
        log.info("Language created [id={}, code={}]", saved.getId(), saved.getCode());
        return mapper.toResponse(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public Language getEntityById(UUID id) {
        return repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Language %s not found".formatted(id)));
    }

    @Override
    @Transactional(readOnly = true)
    public LanguageResponse getById(UUID id) {
        return mapper.toResponse(getEntityById(id));
    }

    @Override
    @Transactional(readOnly = true)
    public Paginated<LanguageResponse> getAll(PaginationFilter filter) {
        return JpaPaginationMapper.findAll(repository, filter, mapper::toResponse);
    }

    @Override
    public LanguageResponse update(LanguageUpdateRequest request) {
        Language language = getEntityById(request.id());
        String code = request.code().toLowerCase(Locale.ROOT);
        rejectDuplicateCode(code, language.getId());

        mapper.updateEntityFromRequest(language, request);
        language.setCode(code);

        Language saved = repository.save(language);
        log.info("Language updated [id={}, code={}]", saved.getId(), saved.getCode());
        return mapper.toResponse(saved);
    }

    @Override
    public LanguageResponse disable(UUID id) {
        Language language = getEntityById(id);
        if (language.getDisabledAt() == null) {
            language.setDisabledAt(Instant.now());
            language = repository.save(language);

            eventPublisher.publish(language.getId(),
                    new LanguageDisabledEvent(language.getId(), language.getDisabledAt()));

            log.info("Language disabled [id={}, code={}]", language.getId(), language.getCode());
        }
        return mapper.toResponse(language);
    }

    @Override
    public LanguageResponse delete(UUID id) {
        Language language = getEntityById(id);
        language.setDeletedAt(Instant.now());

        Language saved = repository.save(language);
        log.info("Language deleted [id={}, code={}]", saved.getId(), saved.getCode());
        return mapper.toResponse(saved);
    }
}
