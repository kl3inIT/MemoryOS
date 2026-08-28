package io.memoryos.worker;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("memoryos.worker")
public record WorkerProperties(boolean enabled, int batchSize, Duration idleDelay) {
}
