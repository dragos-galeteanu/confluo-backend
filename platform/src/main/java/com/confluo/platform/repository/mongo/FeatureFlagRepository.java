package com.confluo.platform.repository.mongo;

import com.confluo.common.mongo.repository.BaseMongoRepository;
import com.confluo.platform.entity.FeatureFlag;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * Feature flags.
 *
 * <p>The only way into the flags, by design: they are managed in the database, so there is no
 * service or controller above this.</p>
 */
@Repository
public interface FeatureFlagRepository extends BaseMongoRepository<FeatureFlag> {
}
