package io.memoryos.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import io.memoryos.ingestion.IngestionCoordinator;
import io.memoryos.ingestion.OperationDispatchPort;
import io.memoryos.ingestion.OperationWorkload;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.trace.ReadableSpan;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;

class RedisStreamWorkerTracingTest {
    @org.junit.jupiter.api.Test
    void acknowledgementFailureDoesNotReclassifyOrDoubleCountCoordinatorOutcome() {
        var registry = new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        var coordinator = new io.memoryos.ingestion.application.DefaultIngestionCoordinator(
                mock(io.memoryos.connector.ConnectorIndexingPort.class),
                mock(io.memoryos.connector.ConnectorCleanupPort.class),
                mock(io.memoryos.document.DocumentCommandPort.class),
                mock(io.memoryos.ingestion.SourceContentExtractor.class),
                mock(io.memoryos.objectstorage.ObjectStorage.class),
                mock(io.memoryos.objectstorage.StoredObjectRegistry.class),
                mock(org.springframework.transaction.support.TransactionTemplate.class),
                mock(java.util.concurrent.ScheduledExecutorService.class),
                mock(io.memoryos.document.ExtractionArtifactPort.class), registry);
        var redis = mock(StringRedisTemplate.class, RETURNS_DEEP_STUBS);
        var settings = new RedisExecutionProperties.Workload("ingestion", "workers", 8);
        var id = RecordId.of("1-0");
        MapRecord<String, Object, Object> record = MapRecord.create("ingestion", Map.<Object, Object>of(
                "tenant_id", UUID.randomUUID().toString(), "operation_kind", "INGESTION",
                "operation_id", UUID.randomUUID().toString(), "delivery_id", UUID.randomUUID().toString()))
                .withId(id);
        when(redis.opsForStream().acknowledge("ingestion", "workers", id))
                .thenThrow(new org.springframework.dao.DataAccessResourceFailureException("test ACK outage"));
        var transportMetrics = mock(RedisExecutionMetrics.class);
        var worker = new RedisStreamWorker(redis, mock(RedisExecutionTopology.class),
                mock(RedisExecutionProperties.class), mock(OperationDispatchPort.class),
                coordinator, transportMetrics, io.opentelemetry.api.OpenTelemetry.noop());

        ReflectionTestUtils.invokeMethod(worker, "process", settings, OperationWorkload.INGESTION, record);

        assertThat(registry.get("memoryos.operation.outcomes").tags("workload", "INGESTION", "outcome", "SKIPPED")
                .counter().count()).isEqualTo(1);
        assertThat(registry.find("memoryos.operation.outcomes").counters().stream()
                .mapToDouble(io.micrometer.core.instrument.Counter::count).sum()).isEqualTo(1);
        verify(transportMetrics).delivery(OperationWorkload.INGESTION, RedisExecutionMetrics.DeliveryOutcome.PENDING);
        verify(redis.opsForStream(), never()).delete("ingestion", id);
    }

    @ParameterizedTest
    @EnumSource(IngestionCoordinator.Outcome.class)
    void handledOutcomesPreserveAcknowledgementAndReportBusinessFailure(IngestionCoordinator.Outcome outcome) {
        try (var provider = SdkTracerProvider.builder().build()) {
            var telemetry = OpenTelemetrySdk.builder().setTracerProvider(provider).build();
            var redis = mock(StringRedisTemplate.class, RETURNS_DEEP_STUBS);
            var settings = new RedisExecutionProperties.Workload("ingestion", "workers", 8);
            var id = RecordId.of("1-0");
            MapRecord<String, Object, Object> record = MapRecord.create("ingestion", Map.<Object, Object>of(
                    "tenant_id", UUID.randomUUID().toString(), "operation_kind", "INGESTION",
                    "operation_id", UUID.randomUUID().toString(), "delivery_id", UUID.randomUUID().toString()))
                    .withId(id);
            when(redis.opsForStream().acknowledge("ingestion", "workers", id)).thenReturn(1L);
            var span = new AtomicReference<ReadableSpan>();
            IngestionCoordinator coordinator = delivery -> {
                span.set((ReadableSpan) Span.current());
                return outcome;
            };
            var metrics = mock(RedisExecutionMetrics.class);
            var worker = new RedisStreamWorker(redis, mock(RedisExecutionTopology.class),
                    mock(RedisExecutionProperties.class), mock(OperationDispatchPort.class),
                    coordinator, metrics, telemetry);

            ReflectionTestUtils.invokeMethod(worker, "process", settings, OperationWorkload.INGESTION, record);

            assertThat(span.get().hasEnded()).isTrue();
            assertThat(span.get().toSpanData().getStatus().getStatusCode())
                    .isEqualTo(outcome == IngestionCoordinator.Outcome.FAILED ? StatusCode.ERROR : StatusCode.UNSET);
            verify(redis.opsForStream()).acknowledge("ingestion", "workers", id);
            verify(redis.opsForStream()).delete("ingestion", id);
            verify(metrics).delivery(OperationWorkload.INGESTION, RedisExecutionMetrics.DeliveryOutcome.ACKED);
            verify(metrics, never()).delivery(OperationWorkload.INGESTION, RedisExecutionMetrics.DeliveryOutcome.PENDING);
        }
    }
}
