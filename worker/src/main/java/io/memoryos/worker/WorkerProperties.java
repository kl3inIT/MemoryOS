package io.memoryos.worker;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Worker tuning read by code. {@code memoryos.worker.enabled} and {@code memoryos.worker.poll-delay} are
 * consumed directly by the scheduling annotations and documented in the additional configuration metadata.
 */
@ConfigurationProperties("memoryos.worker")
public record WorkerProperties(int batchSize) {
}
