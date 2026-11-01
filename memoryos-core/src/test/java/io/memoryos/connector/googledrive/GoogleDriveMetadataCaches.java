package io.memoryos.connector.googledrive;

import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/** Metadata caches assembled as the container assembles them. */
final class GoogleDriveMetadataCaches {
    private GoogleDriveMetadataCaches() { }

    /** The cache of a deployment without Redis: it keeps nothing. */
    static GoogleDriveMetadataCache withoutRedis() {
        return new GoogleDriveMetadataCache(new StaticListableBeanFactory().getBeanProvider(StringRedisTemplate.class));
    }
}
