package com.confluo.platform.repository.jpa;

import com.confluo.common.jpa.repository.BaseJpaRepository;
import com.confluo.platform.entity.Country;
import org.springframework.stereotype.Repository;

import java.util.UUID;

/**
 * Countries, keyed by id and by their ISO codes.
 */
@Repository
public interface CountryRepository extends BaseJpaRepository<Country> {

    /**
     * Whether another country already uses an alpha-2 code.
     * @param alpha2 the code to check
     * @param id the country being updated, excluded from the check
     * @return true if the code is taken
     */
    boolean existsByAlpha2IgnoreCaseAndIdNot(String alpha2, UUID id);

    /**
     * Whether another country already uses an alpha-3 code.
     * @param alpha3 the code to check
     * @param id the country being updated, excluded from the check
     * @return true if the code is taken
     */
    boolean existsByAlpha3IgnoreCaseAndIdNot(String alpha3, UUID id);

    /**
     * Whether another country already uses a numeric code.
     * @param numericCode the code to check
     * @param id the country being updated, excluded from the check
     * @return true if the code is taken
     */
    boolean existsByNumericCodeAndIdNot(String numericCode, UUID id);

    /**
     * Whether any country uses an alpha-2 code.
     * @param alpha2 the code to check
     * @return true if the code is taken
     */
    boolean existsByAlpha2IgnoreCase(String alpha2);

    /**
     * Whether any country uses an alpha-3 code.
     * @param alpha3 the code to check
     * @return true if the code is taken
     */
    boolean existsByAlpha3IgnoreCase(String alpha3);

    /**
     * Whether any country uses a numeric code.
     * @param numericCode the code to check
     * @return true if the code is taken
     */
    boolean existsByNumericCode(String numericCode);
}
