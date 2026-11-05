package com.confluo.payments.pricing;

import com.confluo.common.jpa.entity.BaseJpaEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.HashMap;
import java.util.Map;

/**
 * A tier somebody can subscribe to, as this platform defines it.
 *
 * <p>This service owns the catalog and pushes it into Stripe, rather than mirroring Stripe's. A
 * plan has entitlements and a fee override that mean nothing to Stripe, and a tier nobody can query
 * locally is a tier every other service has to make a network call to read.
 * {@link #stripeProductId} is the reconciliation's result and is null until it has run.</p>
 *
 * <p>The migration carries two constraints this class cannot express: that the key is stored
 * lowercase, and that only a {@link PlanAudience#PROVIDER} plan may set
 * {@link #platformFeeBpsOverride}. The second is there because the mistake is otherwise silent -
 * an override on a customer plan would simply never apply, and the tier would be sold as cheaper
 * than it is.</p>
 */
@Getter
@Setter
@Entity
@Table(
        name = "plans",
        uniqueConstraints = @UniqueConstraint(name = "uq_plans_key", columnNames = "key"))
public class Plan extends BaseJpaEntity {

    /**
     * The stable name this plan is referred to by, e.g. {@code provider_pro}. Lowercase, so that a
     * reference written from memory matches; an id of this service's own is something only this
     * service knows.
     */
    @Column(name = "key", nullable = false, length = 64)
    private String key;

    /** which side of the marketplace it is sold to */
    @Enumerated(EnumType.STRING)
    @Column(name = "audience", nullable = false, length = 16)
    private PlanAudience audience;

    /** what it is called to the person buying it */
    @Column(name = "name", nullable = false, length = 255)
    private String name;

    /** what it offers, in prose, for wherever it is listed */
    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    /**
     * What the plan grants, as a document: the shape differs per plan and per audience, and
     * changing what a tier includes should not cost a migration. Other services read this to
     * decide what somebody may do.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "entitlements", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> entitlements = new HashMap<>();

    /**
     * The platform fee charged to a provider on this tier, in basis points, or null for the
     * default. The one place the subscription half of the business touches the marketplace half:
     * a paid tier can buy a lower cut.
     */
    @Column(name = "platform_fee_bps_override")
    private Integer platformFeeBpsOverride;

    /** Stripe's own {@code prod_} identifier, or null until the catalog has been reconciled */
    @Column(name = "stripe_product_id", length = 255)
    private String stripeProductId;

    /** where it sits in a listing, lowest first */
    @Column(name = "sort_order", nullable = false)
    private int sortOrder = 0;
}
