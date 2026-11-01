package io.memoryos.sandbox;

import io.memoryos.shared.TenantId;
import java.util.UUID;

/** A conversation's sandbox as {@link SandboxService#acquire} found or started it. */
public record Sandbox(TenantId tenant, UUID conversation, String runtimeId) {}
