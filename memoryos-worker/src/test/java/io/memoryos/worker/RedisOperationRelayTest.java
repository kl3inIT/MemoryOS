package io.memoryos.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.memoryos.connector.SourceOperationId;
import io.memoryos.shared.TenantId;
import io.memoryos.ingestion.DispatchClaim;
import io.memoryos.ingestion.OperationDelivery;
import io.memoryos.ingestion.OperationDispatchPort;
import io.memoryos.ingestion.OperationWorkload;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.opentelemetry.api.OpenTelemetry;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.connection.stream.PendingMessagesSummary;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

@SuppressWarnings("unchecked")
class RedisOperationRelayTest {

    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final StreamOperations<String, Object, Object> streams = mock(StreamOperations.class);
    private final OperationDispatchPort dispatch = mock(OperationDispatchPort.class);
    private final RedisExecutionProperties properties = properties();
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final RedisExecutionMetrics metrics = new RedisExecutionMetrics(registry, redis, properties);
    private final RedisOperationRelay relay = new RedisOperationRelay(redis, dispatch, properties, metrics, OpenTelemetry.noop());

    @BeforeEach
    void configureStreamOperations() {
        when(redis.opsForStream()).thenReturn(streams);
    }

    @Test
    void publishesOnlyStableIdentifiersAndRecordsEvidence() {
        DispatchClaim claim = claim();
        when(streams.size(properties.ingestion().stream())).thenReturn(0L);
        when(dispatch.claim(OperationWorkload.INGESTION, properties.ingestion().batchSize()))
                .thenReturn(List.of(claim));
        when(streams.add(eq(properties.ingestion().stream()), anyMap())).thenReturn(RecordId.of("1-0"));

        relay.relay(OperationWorkload.INGESTION);

        var values = ArgumentCaptor.forClass(Map.class);
        verify(streams).add(eq(properties.ingestion().stream()), values.capture());
        assertThat(values.getValue()).containsOnlyKeys(
                "tenant_id",
                "operation_kind",
                "operation_id",
                "delivery_id"
        );
        verify(dispatch).recordPublished(claim, "1-0", properties.rediscoveryDelay());
    }

    @Test
    void stopsBeforeClaimingWhenTheStreamIsAtCapacity() {
        when(streams.size(properties.ingestion().stream())).thenReturn(properties.maxStreamDepth());

        relay.relay(OperationWorkload.INGESTION);

        verifyNoInteractions(dispatch);
    }

    @Test
    void publishesUntilTheStreamHoldsThePublishAheadOfUnreadWork() {
        // MEM-244: one run published one batch, so thirty-two due rows took sixteen runs of the search relay.
        String stream = properties.search().stream();
        List<DispatchClaim> due = Stream.generate(() -> claim(OperationWorkload.SEARCH)).limit(32).toList();
        when(streams.size(stream)).thenReturn(0L, 32L);
        when(dispatch.claim(OperationWorkload.SEARCH, 32)).thenReturn(due);
        when(streams.add(eq(stream), anyMap())).thenReturn(RecordId.of("1-0"));

        relay.relay(OperationWorkload.SEARCH);

        verify(streams, times(32)).add(eq(stream), anyMap());
        // The second pass finds the publish-ahead unread and claims nothing more.
        verify(dispatch, times(1)).claim(eq(OperationWorkload.SEARCH), anyInt());
    }

    @Test
    void workInProgressDoesNotHoldBackNewWork() {
        // A delivered entry stays in the stream until it is acknowledged, so the stream's length counts it too.
        String stream = properties.search().stream();
        var inProgress = mock(PendingMessagesSummary.class);
        when(inProgress.getTotalPendingMessages()).thenReturn(30L);
        when(streams.size(stream)).thenReturn(30L);
        when(streams.pending(stream, properties.search().group())).thenReturn(inProgress);
        when(dispatch.claim(OperationWorkload.SEARCH, 32)).thenReturn(List.of());

        relay.relay(OperationWorkload.SEARCH);

        verify(dispatch).claim(OperationWorkload.SEARCH, 32);
    }

    @Test
    void unreadWorkAtThePublishAheadIsNotAddedTo() {
        String stream = properties.search().stream();
        when(streams.size(stream)).thenReturn(32L);

        relay.relay(OperationWorkload.SEARCH);

        verifyNoInteractions(dispatch);
    }

    @Test
    void stopsTheRunAfterAPublicationFails() {
        String stream = properties.search().stream();
        List<DispatchClaim> due = Stream.generate(() -> claim(OperationWorkload.SEARCH)).limit(32).toList();
        when(streams.size(stream)).thenReturn(0L);
        when(dispatch.claim(OperationWorkload.SEARCH, 32)).thenReturn(due);
        when(streams.add(eq(stream), anyMap())).thenThrow(new DataAccessResourceFailureException("unavailable"));

        relay.relay(OperationWorkload.SEARCH);

        verify(dispatch).defer(due.getFirst(), "REDIS_TRANSPORT_UNAVAILABLE", properties.transportBackoff());
        verify(dispatch, never()).defer(due.get(1), "REDIS_TRANSPORT_UNAVAILABLE", properties.transportBackoff());
    }

    @Test
    void ingestionPressureDoesNotBlockCleanupDispatch() {
        DispatchClaim cleanupClaim = claim(OperationWorkload.CLEANUP);
        when(streams.size(properties.ingestion().stream())).thenReturn(properties.maxStreamDepth());
        when(streams.size(properties.cleanup().stream())).thenReturn(0L);
        when(dispatch.claim(OperationWorkload.CLEANUP, properties.cleanup().batchSize()))
                .thenReturn(List.of(cleanupClaim));
        when(streams.add(eq(properties.cleanup().stream()), anyMap())).thenReturn(RecordId.of("2-0"));

        relay.relay(OperationWorkload.INGESTION);
        relay.relay(OperationWorkload.CLEANUP);

        verify(dispatch).claim(OperationWorkload.CLEANUP, properties.cleanup().batchSize());
        verify(dispatch).recordPublished(cleanupClaim, "2-0", properties.rediscoveryDelay());
    }

    @Test
    void defersTheDurableClaimWhenRedisPublicationFails() {
        DispatchClaim claim = claim();
        when(streams.size(properties.ingestion().stream())).thenReturn(0L);
        when(dispatch.claim(OperationWorkload.INGESTION, properties.ingestion().batchSize()))
                .thenReturn(List.of(claim));
        when(streams.add(eq(properties.ingestion().stream()), anyMap()))
                .thenThrow(new DataAccessResourceFailureException("unavailable"));

        relay.relay(OperationWorkload.INGESTION);

        verify(dispatch).defer(claim, "REDIS_TRANSPORT_UNAVAILABLE", properties.transportBackoff());
    }

    @Test
    void defersAfterPublicationWhenDispatchEvidenceCannotBeRecorded() {
        DispatchClaim claim = claim();
        when(streams.size(properties.ingestion().stream())).thenReturn(0L);
        when(dispatch.claim(OperationWorkload.INGESTION, properties.ingestion().batchSize()))
                .thenReturn(List.of(claim));
        when(streams.add(eq(properties.ingestion().stream()), anyMap())).thenReturn(RecordId.of("1-0"));
        when(dispatch.recordPublished(claim, "1-0", properties.rediscoveryDelay()))
                .thenThrow(new DataAccessResourceFailureException("database unavailable"));

        relay.relay(OperationWorkload.INGESTION);

        verify(dispatch).defer(claim, "REDIS_TRANSPORT_UNAVAILABLE", properties.transportBackoff());
    }

    @Test
    void metricsExposeOnlyBoundedWorkloadAndOutcomeLabels() {
        var tags = registry.getMeters().stream()
                .flatMap(meter -> meter.getId().getTags().stream())
                .toList();

        assertThat(tags).extracting(Tag::getKey)
                .containsOnly("workload", "outcome");
        assertThat(tags).extracting(Tag::getValue)
                .containsOnly(
                        "ingestion",
                        "source_sync",
                        "selection_validation",
                        "cleanup",
                        "search",
                        "user_file",
                        "backpressure",
                        "published",
                        "stale",
                        "deferred",
                        "reclaimed",
                        "invalid",
                        "acked",
                        "ack_missed",
                        "pending"
                );
    }

    private static DispatchClaim claim() {
        return claim(OperationWorkload.INGESTION);
    }

    private static DispatchClaim claim(OperationWorkload workload) {
        return new DispatchClaim(
                new OperationDelivery(
                        new TenantId(UUID.randomUUID()),
                        workload,
                        new SourceOperationId(UUID.randomUUID()),
                        UUID.randomUUID()
                ),
                UUID.randomUUID()
        );
    }

    private static RedisExecutionProperties properties() {
        return new RedisExecutionProperties(
                Duration.ofSeconds(30),
                Duration.ofSeconds(1),
                Duration.ofMinutes(2),
                Duration.ofSeconds(5),
                Duration.ofSeconds(2),
                Duration.ofSeconds(10),
                Duration.ofMinutes(2),
                Duration.ofSeconds(100),
                1_000,
                new RedisExecutionProperties.Workload("ingestion", "ingestion-workers", 8, 0),
                new RedisExecutionProperties.Workload("cleanup", "cleanup-workers", 8, 0),
                new RedisExecutionProperties.Workload("search", "search-workers", 2, 32),
                new RedisExecutionProperties.Workload("sync", "sync-workers", 2, 0),
                new RedisExecutionProperties.Workload("selection", "selection-workers", 2, 0),
                new RedisExecutionProperties.Workload("files", "file-workers", 1, 0)
        );
    }
}
