package com.confluo.payments.pricing;

import com.confluo.common.jpa.repository.BaseJpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;


/**
 * The plan catalog.
 *
 * <p>Read far more than written: a listing per audience, and a lookup by key from wherever a
 * subscription is being created. Both are cheap enough that nothing caches them yet.</p>
 */
@Repository
public interface PlanRepository extends BaseJpaRepository<Plan> {

    /**
     * The plan with a key.
     * @param key the stable name, lowercase
     * @return the plan, or empty if no such tier exists
     */
    Optional<Plan> findByKey(String key);

    /**
     * Whether a key is taken, without loading the row.
     * @param key the stable name, lowercase
     * @return true if a plan already holds it
     */
    boolean existsByKey(String key);

    /**
     * The tiers on offer to one side of the marketplace, in listing order.
     *
     * <p>Filtered on {@code disabledAt} as well as the audience, because a withdrawn tier is
     * disabled rather than deleted - the subscriptions on it still refer to it.</p>
     *
     * @param audience which side is being shown
     * @return the plans they may buy, lowest sort order first
     */
    List<Plan> findByAudienceAndDisabledAtIsNullAndDeletedAtIsNullOrderBySortOrderAsc(PlanAudience audience);
}
