package com.confluo.payments.pricing;

import com.confluo.common.controller.BaseController;
import com.confluo.common.dto.Paginated;
import com.confluo.common.filter.PaginationFilter;
import com.confluo.common.service.BaseService;
import com.confluo.payments.pricing.dto.PlanCreateRequest;
import com.confluo.payments.pricing.dto.PlanResponse;
import com.confluo.payments.pricing.dto.PlanUpdateRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;


/**
 * The tiers this platform sells.
 *
 * <p>Every CRUD route is inherited from {@link BaseController}; the overrides exist only to attach
 * the authorization rule and to state the mapping rather than rely on it being inherited. Three
 * routes are additions: the lookup by key, which is the name every other service actually holds;
 * the listing for one audience, which is what draws a pricing page; and the reconcile, which is how
 * a tier whose push to Stripe failed is made sellable without inventing an update.</p>
 */
@RestController
@RequestMapping("/api/payments/plans")
@RequiredArgsConstructor
@Tag(name = "Plans")
public class PlanController extends BaseController<Plan, PlanCreateRequest, PlanUpdateRequest, PlanResponse> {

    private final PlanService service;

    @Override
    protected BaseService<Plan, PlanCreateRequest, PlanUpdateRequest, PlanResponse> getService() {
        return service;
    }

    @Override
    @PostMapping
    @PreAuthorize("@catalogPolicy.canCreate(authentication, #request)")
    public ResponseEntity<PlanResponse> create(@Valid @RequestBody PlanCreateRequest request) {
        return super.create(request);
    }

    @Override
    @GetMapping("/{id}")
    @PreAuthorize("@catalogPolicy.canGetById(authentication, #id)")
    public ResponseEntity<PlanResponse> getById(@PathVariable UUID id) {
        return super.getById(id);
    }

    /**
     * A tier by its stable key.
     *
     * <p>On its own path segment rather than as a query parameter, so it cannot be confused with a
     * filter and cannot collide with the {@code /{id}} route - a key is not a UUID, and letting the
     * two share a path would make the mapping depend on what the string happened to look like.
     * The same arrangement the notifications service uses for a recipient's uid.</p>
     *
     * @param key the stable name
     * @return the tier
     */
    @GetMapping("/by-key/{key}")
    @Operation(summary = "Get a plan by its stable key")
    @PreAuthorize("@catalogPolicy.canGetById(authentication, null)")
    public ResponseEntity<PlanResponse> getByKey(@PathVariable String key) {
        return ResponseEntity.ok(service.getByKey(key));
    }

    /**
     * The tiers on offer to one side of the marketplace.
     *
     * <p>Unpaginated: there are a handful of tiers and this is what a pricing page asks for, so
     * making every caller handle a page for a list that fits on a screen buys nothing. Disabled and
     * deleted tiers are left out, which the paginated listing does not do - that one is for an
     * administrator, who needs to see what they withdrew.</p>
     *
     * @param audience which side is being shown
     * @return the tiers they may buy, in listing order
     */
    @GetMapping("/offered")
    @Operation(summary = "List the plans on offer to one side of the marketplace")
    @PreAuthorize("@catalogPolicy.canGetById(authentication, null)")
    public ResponseEntity<List<PlanResponse>> getOffered(@ModelAttribute("audience") PlanAudience audience) {
        return ResponseEntity.ok(service.getOffered(audience));
    }

    @Override
    @GetMapping
    @PreAuthorize("@catalogPolicy.canGetAll(authentication, #filter)")
    public ResponseEntity<Paginated<PlanResponse>> getAll(@Valid @ModelAttribute PaginationFilter filter) {
        return super.getAll(filter);
    }

    @Override
    @PutMapping
    @PreAuthorize("@catalogPolicy.canUpdate(authentication, #request)")
    public ResponseEntity<PlanResponse> update(@Valid @RequestBody PlanUpdateRequest request) {
        return super.update(request);
    }

    /**
     * Re-runs a tier's push to Stripe.
     *
     * <p>For a tier created while Stripe was unreachable, which exists here with no
     * {@code stripeProductId} and cannot be priced or sold. Harmless to call on one that is already
     * reconciled: the idempotency key means Stripe hands back the product it already made.</p>
     *
     * @param id which tier
     * @return the tier, with its Stripe id
     */
    @PostMapping("/{id}/reconcile")
    @Operation(summary = "Push a plan into Stripe again")
    @PreAuthorize("@catalogPolicy.canReconcile(authentication)")
    public ResponseEntity<PlanResponse> reconcile(@PathVariable UUID id) {
        return ResponseEntity.ok(service.reconcile(id));
    }

    @Override
    @DeleteMapping("/{id}/disable")
    @PreAuthorize("@catalogPolicy.canDisable(authentication)")
    public ResponseEntity<PlanResponse> disable(@PathVariable UUID id) {
        return super.disable(id);
    }

    @Override
    @DeleteMapping("/{id}")
    @PreAuthorize("@catalogPolicy.canDelete(authentication, #id)")
    public ResponseEntity<PlanResponse> delete(@PathVariable UUID id) {
        return super.delete(id);
    }
}
