package com.confluo.notifications.dto.preference;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

/**
 * Request to set some of a recipient's preferences.
 *
 * <p>A list rather than one setting at a time, and wrapped in a record rather than sent as a bare
 * JSON array: a caller changing three answers at once should have all three applied or none, and a
 * wrapper is also what lets {@code @Valid} reach the elements.</p>
 *
 * <p>Only the settings named are touched. Anything left out keeps whatever it had, which for most
 * combinations is no stored row at all - so this is a patch, not the whole set.</p>
 *
 * @param preferences the settings to apply
 */
public record NotificationPreferencesUpdateRequest(
        @NotEmpty
        @Valid
        List<NotificationPreferenceSetting> preferences
) {
}
