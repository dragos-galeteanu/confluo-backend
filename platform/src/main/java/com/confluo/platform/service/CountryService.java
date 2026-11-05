package com.confluo.platform.service;

import com.confluo.amqp.event.platform.CountryDisabledEvent;
import com.confluo.amqp.publish.EventPublisher;
import com.confluo.common.dto.Paginated;
import com.confluo.common.exception.ResourceNotFoundException;
import com.confluo.common.filter.PaginationFilter;
import com.confluo.common.jpa.mapper.JpaPaginationMapper;
import com.confluo.common.repository.BaseRepository;
import com.confluo.common.service.BaseService;
import com.confluo.platform.dto.country.CountryCreateRequest;
import com.confluo.platform.dto.country.CountryResponse;
import com.confluo.platform.dto.country.CountryUpdateRequest;
import com.confluo.platform.entity.Country;
import com.confluo.platform.entity.Currency;
import com.confluo.platform.entity.Language;
import com.confluo.platform.mapper.CountryMapper;
import com.confluo.platform.repository.jpa.CountryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

/**
 * Countries the platform operates in.
 *
 * <p>A country points at a default currency and a default language by id. An id that matches
 * nothing is a 422 rather than a 404: the request is well-formed and the country it names is not
 * missing - the reference inside it cannot be satisfied.</p>
 */
@Service
@RequiredArgsConstructor
@Transactional
@Slf4j
public class CountryService implements BaseService<Country, CountryCreateRequest, CountryUpdateRequest, CountryResponse> {

    private final CountryRepository repository;
    private final CountryMapper mapper;
    private final EventPublisher eventPublisher;
    private final CurrencyService currencyService;
    private final LanguageService languageService;

    @Override
    public BaseRepository<Country> getRepository() {
        return repository;
    }

    @Override
    public CountryResponse create(CountryCreateRequest request) {
        String alpha2 = request.alpha2().toUpperCase(Locale.ROOT);
        String alpha3 = request.alpha3().toUpperCase(Locale.ROOT);
        rejectDuplicates(alpha2, alpha3, request.numericCode(), null);

        Country country = new Country();
        mapper.createEntityFromRequest(country, request);
        country.setAlpha2(alpha2);
        country.setAlpha3(alpha3);
        country.setDefaultCurrency(resolveCurrency(request.defaultCurrencyId()));
        country.setDefaultLanguage(resolveLanguage(request.defaultLanguageId()));

        Country saved = repository.save(country);
        log.info("Country created [id={}, alpha2={}]", saved.getId(), saved.getAlpha2());
        return mapper.toResponse(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public Country getEntityById(UUID id) {
        return repository.findById(id)
                .filter(country -> country.getDeletedAt() == null)
                .orElseThrow(() -> new ResourceNotFoundException("Country %s not found".formatted(id)));
    }

    @Override
    @Transactional(readOnly = true)
    public CountryResponse getById(UUID id) {
        return mapper.toResponse(getEntityById(id));
    }

    @Override
    @Transactional(readOnly = true)
    public Paginated<CountryResponse> getAll(PaginationFilter filter) {
        return JpaPaginationMapper.findAll(repository, filter, mapper::toResponse);
    }

    @Override
    public CountryResponse update(CountryUpdateRequest request) {
        Country country = getEntityById(request.id());
        String alpha2 = request.alpha2().toUpperCase(Locale.ROOT);
        String alpha3 = request.alpha3().toUpperCase(Locale.ROOT);
        rejectDuplicates(alpha2, alpha3, request.numericCode(), country.getId());

        mapper.updateEntityFromRequest(country, request);
        country.setAlpha2(alpha2);
        country.setAlpha3(alpha3);
        country.setDefaultCurrency(resolveCurrency(request.defaultCurrencyId()));
        country.setDefaultLanguage(resolveLanguage(request.defaultLanguageId()));

        Country saved = repository.save(country);
        log.info("Country updated [id={}, alpha2={}]", saved.getId(), saved.getAlpha2());
        return mapper.toResponse(saved);
    }

    @Override
    public CountryResponse disable(UUID id) {
        Country country = getEntityById(id);
        if (country.getDisabledAt() == null) {
            country.setDisabledAt(Instant.now());
            country = repository.save(country);

            eventPublisher.publish(country.getId(),
                    new CountryDisabledEvent(country.getId(), country.getDisabledAt()));

            log.info("Country disabled [id={}, alpha2={}]", country.getId(), country.getAlpha2());
        }
        return mapper.toResponse(country);
    }

    @Override
    public CountryResponse delete(UUID id) {
        Country country = getEntityById(id);
        country.setDeletedAt(Instant.now());

        Country saved = repository.save(country);
        log.info("Country deleted [id={}, alpha2={}]", saved.getId(), saved.getAlpha2());
        return mapper.toResponse(saved);
    }

    /**
     * Refuses a country whose ISO codes are already taken, so the caller gets a 422 naming the code
     * rather than the database's own constraint violation.
     *
     * <p>On an update the country being changed is excluded, or keeping its own codes would look
     * like a collision with itself.</p>
     *
     * @param alpha2 the alpha-2 code
     * @param alpha3 the alpha-3 code
     * @param numericCode the numeric code
     * @param excludedId the country being updated, or null when creating one
     */
    private void rejectDuplicates(String alpha2, String alpha3, String numericCode, UUID excludedId) {
        boolean alpha2Taken = excludedId == null
                ? repository.existsByAlpha2IgnoreCase(alpha2)
                : repository.existsByAlpha2IgnoreCaseAndIdNot(alpha2, excludedId);
        if (alpha2Taken) {
            throw new IllegalStateException("Country %s already exists".formatted(alpha2));
        }

        boolean alpha3Taken = excludedId == null
                ? repository.existsByAlpha3IgnoreCase(alpha3)
                : repository.existsByAlpha3IgnoreCaseAndIdNot(alpha3, excludedId);
        if (alpha3Taken) {
            throw new IllegalStateException("Country %s already exists".formatted(alpha3));
        }

        boolean numericTaken = excludedId == null
                ? repository.existsByNumericCode(numericCode)
                : repository.existsByNumericCodeAndIdNot(numericCode, excludedId);
        if (numericTaken) {
            throw new IllegalStateException("Country %s already exists".formatted(numericCode));
        }
    }

    /**
     * Looks up the default currency named in a request, through the service that owns currencies -
     * so what counts as a usable currency is decided in one place, not restated here.
     *
     * <p>Its {@link ResourceNotFoundException} is translated on the way out. To that service the
     * currency is the missing resource; to this caller it is a reference inside an otherwise
     * well-formed country, which is a 422 rather than a 404 for a country that exists.</p>
     *
     * @param id the currency id, nullable
     * @return the currency, or null if the request named none
     */
    private Currency resolveCurrency(UUID id) {
        if (id == null) {
            return null;
        }
        try {
            return currencyService.getEntityById(id);
        } catch (ResourceNotFoundException e) {
            throw new IllegalStateException("Currency %s does not exist".formatted(id));
        }
    }

    /**
     * Looks up the default language named in a request, through the service that owns languages.
     * Its {@link ResourceNotFoundException} is translated for the same reason as above.
     * @param id the language id, nullable
     * @return the language, or null if the request named none
     */
    private Language resolveLanguage(UUID id) {
        if (id == null) {
            return null;
        }
        try {
            return languageService.getEntityById(id);
        } catch (ResourceNotFoundException e) {
            throw new IllegalStateException("Language %s does not exist".formatted(id));
        }
    }
}
