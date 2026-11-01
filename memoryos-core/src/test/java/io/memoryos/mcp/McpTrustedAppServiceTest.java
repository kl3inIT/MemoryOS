package io.memoryos.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.memoryos.audit.AuditTrail;
import io.memoryos.iam.IamAccess;
import io.memoryos.iam.IamAuthorization;
import io.memoryos.iam.IamCapability;
import io.memoryos.iam.McpClientPolicy;
import io.memoryos.iam.TenantAccessResolver;
import io.memoryos.mcp.persistence.JdbcMcpTrustedAppRepository;
import io.memoryos.shared.ActorId;
import io.memoryos.shared.TenantId;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * A deployment without an endpoint URL mounts the admin account's secret like any other, but its realm holds no
 * trusted-app policy: nothing reaches Keycloak, the list is read-only and no change is accepted.
 */
class McpTrustedAppServiceTest {
    private final McpClientPolicy policy = mock(McpClientPolicy.class);
    private final IamAuthorization authorization = mock(IamAuthorization.class);
    private final ActorId admin = new ActorId(UUID.randomUUID());
    private final McpTrustedAppService service = new McpTrustedAppService(mock(JdbcMcpTrustedAppRepository.class),
            policy, authorization, mock(TenantAccessResolver.class), mock(AuditTrail.class),
            new McpEndpointProperties(""));

    @BeforeEach
    void anAdministratorWithTheAccount() {
        when(policy.configured()).thenReturn(true);
        var access = mock(IamAccess.class);
        when(access.tenantId()).thenReturn(new TenantId(UUID.randomUUID()));
        when(authorization.require(eq(admin), eq(IamCapability.MCP_MANAGE), anyBoolean())).thenReturn(access);
        when(authorization.lockAndRequireExclusive(admin, IamCapability.MCP_MANAGE)).thenReturn(access);
    }

    @Test
    void withoutAnEndpointTheReconciliationLeavesKeycloakAlone() {
        service.reconcile();
        verify(policy, never()).trust(any());
        verify(policy, never()).removeClientsOutside(any());
    }

    @Test
    void withoutAnEndpointTheListIsReadOnlyAndNoChangeIsAccepted() {
        assertFalse(service.list(admin).manageable());
        var refused = assertThrows(McpException.class,
                () -> service.add(admin, "Agent", List.of("agent.example.com"), List.of()));
        assertEquals("MCP_TRUSTED_APPS_NOT_MANAGEABLE", refused.code());
        verify(policy, never()).trust(any());
    }
}
