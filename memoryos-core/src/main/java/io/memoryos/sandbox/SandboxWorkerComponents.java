package io.memoryos.sandbox;

import io.memoryos.sandbox.persistence.JdbcSandboxRepository;
import org.springframework.context.annotation.Import;

/** What the Worker runs of the sandbox: its reconciliation (MEM-270). The runtime comes from its integration module. */
@Import({ JdbcSandboxRepository.class, SandboxReconciliation.class })
public class SandboxWorkerComponents {
}
