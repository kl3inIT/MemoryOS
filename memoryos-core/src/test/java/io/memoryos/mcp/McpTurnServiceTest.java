package io.memoryos.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.memoryos.iam.Authority;
import io.memoryos.iam.IamAccess;
import io.memoryos.iam.IamAuthorization;
import io.memoryos.iam.IamCapability;
import io.memoryos.mcp.persistence.JpaMcpCredentialRepository;
import io.memoryos.mcp.persistence.JpaMcpServerRepository;
import io.memoryos.mcp.persistence.JpaMcpServerToolRepository;
import io.memoryos.mcp.persistence.McpAccessRepository;
import io.memoryos.mcp.persistence.McpCredentialEntity;
import io.memoryos.mcp.persistence.McpServerEntity;
import io.memoryos.mcp.persistence.McpServerToolEntity;
import io.memoryos.shared.ActorId;
import io.memoryos.shared.TenantId;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

/** Which of a turn's servers become tools and which are reported, with why (MEM-273). */
class McpTurnServiceTest {
    private static final Instant NOW = Instant.parse("2026-10-18T09:00:00Z");
    private final UUID tenant = UUID.randomUUID();
    private final ActorId actor = new ActorId(UUID.randomUUID());
    private final JpaMcpServerRepository servers = mock(JpaMcpServerRepository.class);
    private final JpaMcpServerToolRepository tools = mock(JpaMcpServerToolRepository.class);
    private final JpaMcpCredentialRepository credentials = mock(JpaMcpCredentialRepository.class);
    private final McpAccessRepository access = mock(McpAccessRepository.class);
    private final IamAuthorization authorization = mock(IamAuthorization.class);
    private McpTurnService turns;

    @BeforeEach
    void setUp() {
        var manager = mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        when(authorization.require(actor, IamCapability.CHAT_WRITE, false))
                .thenReturn(new IamAccess(new TenantId(tenant), Authority.GLOBAL));
        when(authorization.effectiveCapabilities(actor)).thenReturn(Set.of(IamCapability.CHAT_WRITE));
        turns = new McpTurnService(servers, tools, credentials, access, authorization, mock(McpSecrets.class),
                mock(McpClients.class), mock(McpOAuthService.class), mock(McpServerService.class), manager);
    }

    @Test
    void reportsTheAssistantsServersThePersonCouldNotSelectAndOffersOnlyTheSelectedOnes() {
        var crm = server("crm", "CRM", McpAuthType.NONE, McpAuthPerformer.ADMIN, 1);
        var docs = server("docs", "Docs", McpAuthType.NONE, McpAuthPerformer.ADMIN, 1);
        var linear = server("linear", "Linear", McpAuthType.OAUTH, McpAuthPerformer.PER_USER, 1);
        var jira = server("jira", "Jira", McpAuthType.OAUTH, McpAuthPerformer.ADMIN, 1);
        var wiki = server("wiki", "Wiki", McpAuthType.NONE, McpAuthPerformer.ADMIN, 0);
        var payroll = server("payroll", "Payroll", McpAuthType.NONE, McpAuthPerformer.ADMIN, 1);
        var hidden = server("hidden", "Hidden", McpAuthType.NONE, McpAuthPerformer.ADMIN, 1);
        when(access.accessibleServerIds(tenant, actor.value()))
                .thenReturn(Set.of(crm.getId(), docs.getId(), linear.getId(), jira.getId(), wiki.getId()));
        when(credentials.findByTenantIdAndServerIdAndOwnerActorId(tenant, linear.getId(), actor.value())).thenReturn(Optional.empty());
        var expired = new McpCredentialEntity(UUID.randomUUID(), tenant, jira.getId(), null, NOW);
        expired.requireReauthorization(NOW);
        when(credentials.findByTenantIdAndServerIdAndOwnerActorIdIsNull(tenant, jira.getId())).thenReturn(Optional.of(expired));

        // The composer sends only usable servers; Docs is usable but turned off, Hidden is selected but not attached.
        try (var turn = turns.open(actor, List.of(crm.getId(), hidden.getId()),
                List.of(crm.getId(), docs.getId(), linear.getId(), jira.getId(), wiki.getId(), payroll.getId()))) {
            assertEquals(List.of("mcp_crm_tool_0"), turn.bindings().stream().map(McpTurnTools.Binding::modelName).toList());
            assertEquals(Map.of(
                            "Linear", McpTurnTools.Unavailable.Reason.NOT_CONNECTED,
                            "Jira", McpTurnTools.Unavailable.Reason.REAUTH_REQUIRED,
                            "Wiki", McpTurnTools.Unavailable.Reason.NO_TOOLS,
                            "Payroll", McpTurnTools.Unavailable.Reason.INACCESSIBLE),
                    turn.unavailable().stream().collect(Collectors.toMap(McpTurnTools.Unavailable::serverName,
                            McpTurnTools.Unavailable::reason)));
            assertEquals(Set.of("Linear", "Jira"), turn.unavailable().stream().filter(McpTurnTools.Unavailable::connectable)
                    .map(McpTurnTools.Unavailable::serverName).collect(Collectors.toSet()));
        }
        // A selected server the actor may not use and the assistant does not attach is not even read.
        verify(servers, never()).findByTenantIdAndId(tenant, hidden.getId());
    }

    @Test
    void aTurnWithoutSelectedOrAttachedServersOpensNothing() {
        try (var turn = turns.open(actor, List.of(), List.of())) {
            assertEquals(List.of(), turn.bindings());
            assertEquals(List.of(), turn.unavailable());
        }
        verify(authorization, never()).require(eq(actor), any(), eq(false));
    }

    private McpServerEntity server(String slug, String name, McpAuthType type, McpAuthPerformer performer, int enabledTools) {
        var server = new McpServerEntity(UUID.randomUUID(), tenant, slug, NOW);
        server.configure(name, null, "http://10.0.0.5/mcp", type, performer, null, "[]", "{}", null, true, Set.of(), NOW);
        when(servers.findByTenantIdAndId(tenant, server.getId())).thenReturn(Optional.of(server));
        var listed = new ArrayList<McpServerToolEntity>();
        for (int i = 0; i < Math.max(enabledTools, 1); i++) {
            var tool = new McpServerToolEntity(UUID.randomUUID(), tenant, server.getId(), "tool_" + i);
            tool.snapshot(null, "Tool " + i, "{\"type\":\"object\"}", "{}", true, NOW);
            // A server with no enabled tool still lists one, turned off.
            tool.policy(enabledTools > 0 ? McpToolPolicy.ALWAYS : McpToolPolicy.OFF, true);
            listed.add(tool);
        }
        when(tools.findByTenantIdAndServerIdOrderByNameAsc(tenant, server.getId())).thenReturn(listed);
        return server;
    }
}
