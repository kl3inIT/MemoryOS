package io.memoryos.sandbox;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * What a new sandbox is started with. Egress is denied except to {@code allowedDomains}, always: the MEM-270 probe
 * found a sandbox without a network policy reaches the host's ports.
 */
public record SandboxSpec(String image, Duration timeToLive, String cpu, String memory, List<String> allowedDomains,
                          Map<String, String> labels) {
    public SandboxSpec {
        if (image.isBlank()) throw new IllegalArgumentException("A sandbox needs an image");
        if (timeToLive.isNegative() || timeToLive.isZero()) throw new IllegalArgumentException("A sandbox needs a life");
        allowedDomains = List.copyOf(allowedDomains);
        labels = Map.copyOf(labels);
    }
}
