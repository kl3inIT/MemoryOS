package io.memoryos.connector.googledrive;

import io.memoryos.connector.GoogleDriveProvider.FileMetadata;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Short-lived Google Drive file metadata, shared by the browsing requests of one credential. Expanding a folder
 * proves that it is still inside the selected scope by walking its ancestors, so a chain one level deeper repeats
 * every read of the level above it; this cache answers those repeats without a Drive call. Only selection browsing
 * reads it: synchronization and indexing always read Drive directly, so nothing is indexed from a cached fact.
 */
@Component
public class GoogleDriveMetadataCache {
    private static final Logger LOG = LoggerFactory.getLogger(GoogleDriveMetadataCache.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String PREFIX = "memoryos:drive:meta:";
    /** Short enough that a file moved out of scope stops expanding within a browsing session. */
    private static final Duration TTL = Duration.ofSeconds(60);

    private final @Nullable StringRedisTemplate redis;

    // Marked, because the container would otherwise prefer the cacheless constructor kept for tests.
    @Autowired
    public GoogleDriveMetadataCache(ObjectProvider<StringRedisTemplate> redis) {
        this(redis.getIfAvailable());
        if (this.redis == null) LOG.info("Google Drive metadata is not cached: no Redis template is available");
    }

    GoogleDriveMetadataCache(@Nullable StringRedisTemplate redis) {
        this.redis = redis;
    }

    /** A cache that keeps nothing, for callers assembled without Redis. */
    GoogleDriveMetadataCache() {
        this((StringRedisTemplate) null);
    }

    public Optional<FileMetadata> find(String scope, String id) {
        if (redis == null) return Optional.empty();
        try {
            String stored = redis.opsForValue().get(key(scope, id));
            return stored == null ? Optional.empty() : Optional.of(JSON.readValue(stored, Entry.class).toMetadata());
        } catch (RuntimeException exception) {
            LOG.debug("Drive metadata cache read failed", exception);
            return Optional.empty();
        }
    }

    public void put(String scope, FileMetadata file) {
        putAll(scope, List.of(file));
    }

    /** Listing a folder already carries its children, so their own expansion starts from a warm cache. */
    public void putAll(String scope, Collection<FileMetadata> files) {
        if (redis == null || files.isEmpty()) return;
        try {
            for (var file : files) {
                redis.opsForValue().set(key(scope, file.id()), JSON.writeValueAsString(Entry.of(file)), TTL);
            }
        } catch (RuntimeException exception) {
            LOG.debug("Drive metadata cache write failed", exception);
        }
    }

    private static String key(String scope, String id) {
        return PREFIX + scope + ":" + id;
    }

    /** The stored shape: an explicit epoch keeps the entry readable without date configuration. */
    private record Entry(String id, String name, String mimeType, String version, @Nullable String checksum,
                         @Nullable Long modifiedAt, boolean trashed, List<String> parents,
                         @Nullable String driveId, @Nullable String shortcutTargetId) {
        static Entry of(FileMetadata file) {
            return new Entry(file.id(), file.name(), file.mimeType(), file.version(), file.checksum(),
                    file.modifiedAt() == null ? null : file.modifiedAt().toEpochMilli(), file.trashed(),
                    file.parents(), file.driveId(), file.shortcutTargetId());
        }

        FileMetadata toMetadata() {
            return new FileMetadata(id, name, mimeType, version, checksum,
                    modifiedAt == null ? null : Instant.ofEpochMilli(modifiedAt), trashed, parents,
                    driveId, shortcutTargetId);
        }
    }
}
