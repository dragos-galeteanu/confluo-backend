package com.confluo.platform.service;

import com.confluo.amqp.event.platform.CurrencyDisabledEvent;
import com.confluo.amqp.publish.EventPublisher;
import com.confluo.common.dto.Paginated;
import com.confluo.common.exception.ResourceNotFoundException;
import com.confluo.common.filter.PaginationFilter;
import com.confluo.common.jpa.mapper.JpaPaginationMapper;
import com.confluo.common.repository.BaseRepository;
import com.confluo.common.service.BaseService;
import com.confluo.platform.dto.currency.CurrencyCreateRequest;
import com.confluo.platform.dto.currency.CurrencyResponse;
import com.confluo.platform.dto.currency.CurrencyUpdateRequest;
import com.confluo.platform.entity.Currency;
import com.confluo.platform.mapper.CurrencyMapper;
import com.confluo.platform.repository.jpa.CurrencyRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

/**
 * Currencies the platform can price in.
 */
@Service
@RequiredArgsConstructor
@Transactional
@Slf4j
public class CurrencyService implements BaseService<Currency, CurrencyCreateRequest, CurrencyUpdateRequest, CurrencyResponse> {

    private final CurrencyRepository repository;
    private final CurrencyMapper mapper;
    private final EventPublisher eventPublisher;

    /**
     * Refuses a code another currency already holds, so the caller gets a 422 naming the code rather
     * than the database's own constraint violation. On an update the currency being changed is
     * excluded, or keeping its own code would look like a collision with itself.
     * @param code the code to check
     * @param excludedId the currency being updated, or null when creating one
     */
    private void rejectDuplicateCode(String code, UUID excludedId) {
        boolean taken = excludedId == null
                ? repository.existsByCodeIgnoreCase(code)
                : repository.existsByCodeIgnoreCaseAndIdNot(code, excludedId);
        if (taken) {
            throw new IllegalStateException("Currency %s already exists".formatted(code));
        }
    }

    @Override
    public BaseRepository<Currency> getRepository() {
        return repository;
    }

    @Override
    public CurrencyResponse create(CurrencyCreateRequest request) {
        String code = request.code().toUpperCase(Locale.ROOT);
        rejectDuplicateCode(code, null);

        Currency currency = new Currency();
        mapper.createEntityFromRequest(currency, request);
        currency.setCode(code);

        Currency saved = repository.save(currency);
        log.info("Currency created [id={}, code={}]", saved.getId(), saved.getCode());
        return mapper.toResponse(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public Currency getEntityById(UUID id) {
        return repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Currency %s not found".formatted(id)));
    }

    @Override
    @Transactional(readOnly = true)
    public CurrencyResponse getById(UUID id) {
        return mapper.toResponse(getEntityById(id));
    }

    @Override
    @Transactional(readOnly = true)
    public Paginated<CurrencyResponse> getAll(PaginationFilter filter) {
        return JpaPaginationMapper.findAll(repository, filter, mapper::toResponse);
    }

    @Override
    public CurrencyResponse update(CurrencyUpdateRequest request) {
        Currency currency = getEntityById(request.id());
        String code = request.code().toUpperCase(Locale.ROOT);
        rejectDuplicateCode(code, currency.getId());

        mapper.updateEntityFromRequest(currency, request);
        currency.setCode(code);

        Currency saved = repository.save(currency);
        log.info("Currency updated [id={}, code={}]", saved.getId(), saved.getCode());
        return mapper.toResponse(saved);
    }

    @Override
    public CurrencyResponse disable(UUID id) {
        Currency currency = getEntityById(id);
        if (currency.getDisabledAt() == null) {
            currency.setDisabledAt(Instant.now());
            currency = repository.save(currency);

            eventPublisher.publish(currency.getId(),
                    new CurrencyDisabledEvent(currency.getId(), currency.getDisabledAt()));

            log.info("Currency disabled [id={}, code={}]", currency.getId(), currency.getCode());
        }
        return mapper.toResponse(currency);
    }

    @Override
    public CurrencyResponse delete(UUID id) {
        Currency currency = getEntityById(id);
        currency.setDeletedAt(Instant.now());

        Currency saved = repository.save(currency);
        log.info("Currency deleted [id={}, code={}]", saved.getId(), saved.getCode());
        return mapper.toResponse(saved);
    }
}
