package io.memoryos.api.mcp.endpoint;

import io.memoryos.mcp.McpTrustedAppService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * MEM-207: brings Keycloak's metadata-document policy back to the trusted apps the database holds, when the API starts
 * and every ten minutes, so a change whose transaction failed after Keycloak took it, or an edit made in Keycloak's
 * console, does not last. Each pass is two reads and writes only what differs.
 */
@Component
class McpTrustedAppReconciliation {
    private static final Logger LOGGER = LoggerFactory.getLogger(McpTrustedAppReconciliation.class);
    private final McpTrustedAppService trustedApps;

    McpTrustedAppReconciliation(McpTrustedAppService trustedApps) {
        this.trustedApps = trustedApps;
    }

    @EventListener(ApplicationReadyEvent.class)
    void onStart() {
        reconcile();
    }

    @Scheduled(initialDelayString = "${memoryos.mcp.endpoint.trusted-app-reconcile-interval:10m}",
            fixedDelayString = "${memoryos.mcp.endpoint.trusted-app-reconcile-interval:10m}")
    void reconcile() {
        try {
            trustedApps.reconcile();
        } catch (RuntimeException failure) {
            LOGGER.atWarn().addKeyValue("event", "mcp_endpoint.trusted_apps.reconcile_failed")
                    .addKeyValue("error_type", failure.getClass().getName()).log("MCP trusted apps not reconciled");
        }
    }
}
