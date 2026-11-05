package com.confluo.platform.entity;

import com.confluo.common.mongo.entity.BaseMongoDocument;
import lombok.Getter;
import lombok.Setter;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * A switch that turns part of the platform on or off.
 *
 * <p>Flags are managed in the database rather than through an API, so this document has no service,
 * controller or DTOs - only a repository. A flag is <em>on</em> while its {@code disabledAt} is
 * null, which is the same meaning {@code disabledAt} carries everywhere else; turning a flag off is
 * therefore a dated act that can be read back, and is not the same as deleting the flag.</p>
 */
@Getter
@Setter
@Document(collection = "feature_flags")
public class FeatureFlag extends BaseMongoDocument {

    /** the key callers look the flag up by, e.g. checkout-v2; unique across the platform */
    @Indexed(unique = true)
    private String flagKey;

    /** a short human-readable name, for whoever is reading the collection */
    private String name;

    /** what the flag controls, and anything a future reader needs to know before flipping it */
    private String description;
}
