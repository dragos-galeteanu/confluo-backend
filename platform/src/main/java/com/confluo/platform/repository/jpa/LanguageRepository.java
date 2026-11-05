package com.confluo.platform.repository.jpa;

import com.confluo.common.jpa.repository.BaseJpaRepository;
import com.confluo.platform.entity.Language;
import org.springframework.stereotype.Repository;

import java.util.UUID;

/**
 * Languages, keyed by id and by their ISO 639-1 code.
 */
@Repository
public interface LanguageRepository extends BaseJpaRepository<Language> {

    /**
     * Whether another language already uses a code.
     * @param code the code to check
     * @param id the language being updated, excluded from the check
     * @return true if the code is taken
     */
    boolean existsByCodeIgnoreCaseAndIdNot(String code, UUID id);

    /**
     * Whether any language uses a code.
     * @param code the code to check
     * @return true if the code is taken
     */
    boolean existsByCodeIgnoreCase(String code);
}
