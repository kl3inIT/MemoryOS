package io.memoryos.connector.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.memoryos.connector.GoogleDriveProvider.FileMetadata;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

class GoogleDriveMetadataCacheTest {
    private static final FileMetadata FILE = new FileMetadata("folder-1", "Quarterly reports",
            "application/vnd.google-apps.folder", "7", "checksum", Instant.parse("2026-09-20T10:15:30Z"),
            false, List.of("parent-1", "parent-2"), "drive-1", null);

    @Test
    void keepsEveryRecordedFactOfAFileWithinOneCredentialScope() {
        var stored = new HashMap<String, String>();
        var cache = new GoogleDriveMetadataCache(redisBackedBy(stored));

        cache.put("tenant|credential|3", FILE);

        assertThat(cache.find("tenant|credential|3", "folder-1")).hasValue(FILE);
        assertThat(stored).hasSize(1);
    }

    @Test
    void doesNotAnswerForAnotherCredentialOrAnotherRevisionOfIt() {
        var cache = new GoogleDriveMetadataCache(redisBackedBy(new HashMap<>()));
        cache.put("tenant|credential|3", FILE);

        assertThat(cache.find("tenant|credential|4", "folder-1")).isEmpty();
        assertThat(cache.find("tenant|other|3", "folder-1")).isEmpty();
        assertThat(cache.find("tenant|credential|3", "folder-2")).isEmpty();
    }

    @Test
    void treatsAnUnavailableCacheAsAMiss() {
        var redis = mock(StringRedisTemplate.class);
        when(redis.opsForValue()).thenThrow(new IllegalStateException("Redis is unavailable"));
        var cache = new GoogleDriveMetadataCache(redis);

        cache.put("tenant|credential|3", FILE);

        assertThat(cache.find("tenant|credential|3", "folder-1")).isEmpty();
    }

    @Test
    void keepsNothingWhenNoRedisIsConfigured() {
        var cache = new GoogleDriveMetadataCache();

        cache.putAll("tenant|credential|3", List.of(FILE));

        assertThat(cache.find("tenant|credential|3", "folder-1")).isEmpty();
    }

    @SuppressWarnings("unchecked")
    private static StringRedisTemplate redisBackedBy(Map<String, String> stored) {
        var redis = mock(StringRedisTemplate.class);
        var values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        doAnswer(invocation -> stored.put(invocation.getArgument(0), invocation.getArgument(1)))
                .when(values).set(anyString(), anyString(), any(Duration.class));
        when(values.get(anyString())).thenAnswer(invocation -> stored.get(invocation.<String>getArgument(0)));
        return redis;
    }
}
