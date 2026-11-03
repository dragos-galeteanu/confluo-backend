package com.confluo.notifications.entity;

import com.confluo.common.jpa.entity.BaseJpaEntity;
import com.confluo.notifications.channel.Category;
import com.confluo.notifications.channel.Channel;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.Setter;

/**
 * One person's answer about one kind of message on one channel.
 *
 * <p>There is no row for most combinations, and that is not an omission: absence means the
 * service's default, which is on for {@link Category#TRANSACTIONAL} and {@link Category#ACCOUNT}
 * and off for {@link Category#MARKETING}. Writing every combination out for every recipient would
 * be a row count with no information in it, and would make changing a default a migration.</p>
 *
 * <p>A {@code TRANSACTIONAL} preference can exist but cannot be {@code false}, which the database
 * enforces through {@code ck_notification_preferences_transactional}. The pipeline enforces it too.
 * Both, deliberately: a hole here is invisible, because nobody complains about the message they did
 * not get.</p>
 */
@Getter
@Setter
@Entity
@Table(
        name = "notification_preferences",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_notification_preferences_scope",
                columnNames = {"recipient_id", "channel", "category"}))
public class NotificationPreference extends BaseJpaEntity {

    /** whose preference this is */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "recipient_id", nullable = false)
    private Recipient recipient;

    /** the channel it applies to */
    @Enumerated(EnumType.STRING)
    @Column(name = "channel", nullable = false, length = 16)
    private Channel channel;

    /** the kind of message it applies to */
    @Enumerated(EnumType.STRING)
    @Column(name = "category", nullable = false, length = 16)
    private Category category;

    /** whether they want it */
    @Column(name = "enabled", nullable = false)
    private boolean enabled;
}
