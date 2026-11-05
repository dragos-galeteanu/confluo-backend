package com.confluo.payments.pricing;

import com.confluo.common.dto.Paginated;
import com.confluo.common.exception.ResourceNotFoundException;
import com.confluo.common.filter.PaginationFilter;
import com.confluo.common.jpa.mapper.JpaPaginationMapper;
import com.confluo.common.repository.BaseRepository;
import com.confluo.common.service.BaseService;
import com.confluo.payments.pricing.dto.PlanCreateRequest;
import com.confluo.payments.pricing.dto.PlanResponse;
import com.confluo.payments.pricing.dto.PlanUpdateRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.UUID;


/**
 * The tiers this platform sells.
 *
 * <p><strong>Not {@code @Transactional} at the class level</strong>, unlike the other services
 * written against {@link BaseService}. Every write here makes a call to Stripe, and a transaction
 * spanning it would hold a database connection open across a network round trip. The local row is
 * saved first and committed on its own, then Stripe is reconciled, then the resulting id is saved -
 * the same order, and for the same reason, as {@code OrderCheckoutService}.</p>
 *
 * <p>So a reconciliation that fails leaves a tier whose {@code stripeProductId} is null. That is
 * visible rather than hidden: the response carries the null, a price cannot be added to such a
 * tier, and {@link #reconcile} re-runs the push. A tier that exists locally and not at Stripe is a
 * tier that cannot be sold, which is the safe way for this to fail.</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PlanService implements BaseService<Plan, PlanCreateRequest, PlanUpdateRequest, PlanResponse> {

    private final PlanRepository repository;
    private final PlanMapper mapper;
    private final StripeCatalogReconciler reconciler;

    @Override
    public BaseRepository<Plan> getRepository() {
        return repository;
    }

    @Override
    public PlanResponse create(PlanCreateRequest request) {
        String key = request.key().toLowerCase(Locale.ROOT);
        if (repository.existsByKey(key)) {
            throw new IllegalStateException("A plan with the key %s already exists".formatted(key));
        }
        requireFeeOverrideIsForProviders(request.audience(), request.platformFeeBpsOverride());

        Plan plan = new Plan();
        mapper.createEntityFromRequest(plan, request);
        plan.setKey(key);
        if (plan.getEntitlements() == null) {
            // The column is NOT NULL; an omitted field means "grants nothing stated", not null.
            plan.setEntitlements(new HashMap<>());
        }
        plan = repository.save(plan);

        plan.setStripeProductId(reconciler.reconcile(plan));
        Plan saved = repository.save(plan);

        log.info("Plan created [id={}, key={}, audience={}, product={}]",
                saved.getId(), saved.getKey(), saved.getAudience(), saved.getStripeProductId());
        return mapper.toResponse(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public Plan getEntityById(UUID id) {
        return repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Plan %s not found".formatted(id)));
    }

    /**
     * The tier with a key, for the callers that hold one rather than an id - which is most of them,
     * since the key is the only name of a tier that is written down outside this service.
     *
     * @param key the stable name, lowercase
     * @return the tier
     * @throws ResourceNotFoundException if no such tier exists
     */
    @Transactional(readOnly = true)
    public Plan getEntityByKey(String key) {
        return repository.findByKey(key.toLowerCase(Locale.ROOT))
                .orElseThrow(() -> new ResourceNotFoundException("Plan %s not found".formatted(key)));
    }

    @Override
    @Transactional(readOnly = true)
    public PlanResponse getById(UUID id) {
        return mapper.toResponse(getEntityById(id));
    }

    /**
     * A tier by key, as the API returns it.
     * @param key the stable name
     * @return the tier
     */
    @Transactional(readOnly = true)
    public PlanResponse getByKey(String key) {
        return mapper.toResponse(getEntityByKey(key));
    }

    @Override
    @Transactional(readOnly = true)
    public Paginated<PlanResponse> getAll(PaginationFilter filter) {
        return JpaPaginationMapper.findAll(repository, filter, mapper::toResponse);
    }

    /**
     * The tiers on offer to one side of the marketplace, in listing order.
     *
     * <p>Unpaginated and unfiltered, because this is the one every client calls to draw a pricing
     * page: there are a handful of tiers, and asking for them as a page would make every caller
     * handle pagination for a list that fits on a screen.</p>
     *
     * @param audience which side is being shown
     * @return the tiers they may buy
     */
    @Transactional(readOnly = true)
    public List<PlanResponse> getOffered(PlanAudience audience) {
        return repository
                .findByAudienceAndDisabledAtIsNullAndDeletedAtIsNullOrderBySortOrderAsc(audience)
                .stream()
                .map(mapper::toResponse)
                .toList();
    }

    @Override
    public PlanResponse update(PlanUpdateRequest request) {
        Plan plan = getEntityById(request.id());
        requireFeeOverrideIsForProviders(plan.getAudience(), request.platformFeeBpsOverride());

        mapper.updateEntityFromRequest(plan, request);
        if (plan.getEntitlements() == null) {
            plan.setEntitlements(new HashMap<>());
        }
        plan = repository.save(plan);

        // Name and description are what a payer sees on Stripe's hosted page, so a tier renamed
        // here and not there would be renamed everywhere except where it is sold.
        plan.setStripeProductId(reconciler.reconcile(plan));
        Plan saved = repository.save(plan);

        log.info("Plan updated [id={}, key={}]", saved.getId(), saved.getKey());
        return mapper.toResponse(saved);
    }

    /**
     * Re-runs the push to Stripe for a tier whose reconciliation failed.
     *
     * <p>Exists because the alternative is a tier stuck unsellable with no way to retry but an
     * update that changes nothing. The idempotency key means running it on a tier that is already
     * reconciled is harmless.</p>
     *
     * @param id which tier
     * @return the tier, with its Stripe id
     */
    public PlanResponse reconcile(UUID id) {
        Plan plan = getEntityById(id);
        plan.setStripeProductId(reconciler.reconcile(plan));
        Plan saved = repository.save(plan);
        log.info("Plan reconciled [id={}, key={}, product={}]",
                saved.getId(), saved.getKey(), saved.getStripeProductId());
        return mapper.toResponse(saved);
    }

    /**
     * Stops offering a tier, without touching Stripe.
     *
     * <p>Deliberately local. Every sale and every subscription goes through this service, which
     * reads {@code disabledAt}, so a disabled tier cannot be bought here - and leaving the Stripe
     * product alone is what makes this reversible, which archiving it at Stripe would not be.
     * {@link #delete} is the one that is final.</p>
     *
     * @param id which tier
     * @return the tier
     */
    @Override
    public PlanResponse disable(UUID id) {
        Plan plan = getEntityById(id);
        if (plan.getDisabledAt() == null) {
            plan.setDisabledAt(Instant.now());
            plan = repository.save(plan);
            log.info("Plan disabled [id={}, key={}]", plan.getId(), plan.getKey());
        }
        return mapper.toResponse(plan);
    }

    /**
     * Retires a tier for good, and archives its Stripe product.
     *
     * <p>Soft-deleted here, archived there. Neither removes anything: the subscriptions on this
     * tier's prices still refer to them and are still billed, which is why Stripe does not allow a
     * product with prices to be deleted either. What both do is stop it being sold again.</p>
     *
     * @param id which tier
     * @return the tier
     */
    @Override
    public PlanResponse delete(UUID id) {
        Plan plan = getEntityById(id);
        plan.setDeletedAt(Instant.now());
        Plan saved = repository.save(plan);

        reconciler.archiveProduct(saved.getStripeProductId());

        log.info("Plan deleted [id={}, key={}]", saved.getId(), saved.getKey());
        return mapper.toResponse(saved);
    }

    /**
     * Refuses a fee override on a tier that does not pay the platform fee.
     *
     * <p>{@code ck_plans_fee_override_is_provider_only} would refuse it too, as a
     * {@code DataIntegrityViolationException} that names a constraint rather than the mistake.
     * Checked here so the answer says which field is the problem and why.</p>
     *
     * @param audience the tier's audience
     * @param override the override asked for, or null
     */
    private void requireFeeOverrideIsForProviders(PlanAudience audience, Integer override) {
        if (override != null && audience != PlanAudience.PROVIDER) {
            throw new IllegalStateException(
                    "A platform fee override only applies to a PROVIDER plan; this one is %s, and "
                            .formatted(audience)
                            + "customers do not pay the platform fee");
        }
    }
}
