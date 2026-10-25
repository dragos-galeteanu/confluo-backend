package com.confluo.platform.repository.jpa;

import com.confluo.common.jpa.repository.BaseJpaRepository;
import com.confluo.platform.entity.Currency;
import org.springframework.stereotype.Repository;

import java.util.UUID;

/**
 * Currencies, keyed by id and by their ISO 4217 code.
 */
@Repository
public interface CurrencyRepository extends BaseJpaRepository<Currency> {

    /**
     * Whether another currency already uses a code.
     * @param code the code to check
     * @param id the currency being updated, excluded from the check
     * @return true if the code is taken
     */
    boolean existsByCodeIgnoreCaseAndIdNot(String code, UUID id);

    /**
     * Whether any currency uses a code.
     * @param code the code to check
     * @return true if the code is taken
     */
    boolean existsByCodeIgnoreCase(String code);
}
