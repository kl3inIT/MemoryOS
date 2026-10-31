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
    private final McpSecrets secrets = mock(McpSecrets.class);
    private McpTurnService turns;

    @BeforeEach
    void setUp() {
        var manager = mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        when(authorization.require(actor, IamCapability.CHAT_WRITE, false))
                .thenReturn(new IamAccess(new TenantId(tenant), Authority.GLOBAL));
        when(authorization.effectiveCapabilities(actor)).thenReturn(Set.of(IamCapability.CHAT_WRITE));
        when(secrets.open(any(), any(), any(), any())).thenReturn("{\"api_key\":\"key\"}");
        turns = new McpTurnService(servers, tools, credentials, access, authorization, secrets,
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

    @Test
    void aMembersDirectMessageOpensEveryAttachedServerWithTheirOwnConnectionAndNoToolThatAsksFirst() {
        var gmail = server("gmail", "Gmail", McpAuthType.API_TOKEN, McpAuthPerformer.PER_USER, 0);
        tools(gmail, tool(gmail, "read_mail", true, McpToolPolicy.ALWAYS), tool(gmail, "send_mail", false, McpToolPolicy.ASK));
        var mine = new McpCredentialEntity(UUID.randomUUID(), tenant, gmail.getId(), actor.value(), NOW);
        when(credentials.findByTenantIdAndServerIdAndOwnerActorId(tenant, gmail.getId(), actor.value())).thenReturn(Optional.of(mine));
        when(access.accessibleServerIds(tenant, actor.value())).thenReturn(Set.of(gmail.getId()));

        // Nothing is selected in a channel: every attached server is.
        try (var turn = turns.open(actor, List.of(), List.of(gmail.getId()), Set.of(), McpTurnService.Surface.MEMBER_CHANNEL)) {
            assertEquals(List.of("mcp_gmail_read_mail"), names(turn));
            assertEquals(List.of(), turn.unavailable());
            assertEquals(true, turn.channel());
        }
    }

    @Test
    void aGroupsTurnOnTheBotsAccountUsesSharedServersOnlyAndOffersNoToolThatAsksFirst() {
        var linear = server("linear", "Linear", McpAuthType.NONE, McpAuthPerformer.ADMIN, 0);
        tools(linear, tool(linear, "list_issues", true, McpToolPolicy.ALWAYS),
                tool(linear, "create_issue", false, McpToolPolicy.ALWAYS), tool(linear, "delete_issue", false, McpToolPolicy.ASK));
        var gmail = server("gmail", "Gmail", McpAuthType.API_TOKEN, McpAuthPerformer.PER_USER, 1);
        when(access.accessibleServerIds(tenant, actor.value())).thenReturn(Set.of(linear.getId(), gmail.getId()));

        try (var turn = turns.open(actor, List.of(), List.of(linear.getId(), gmail.getId()), Set.of(),
                McpTurnService.Surface.BOT_CHANNEL)) {
            assertEquals(List.of("mcp_linear_create_issue", "mcp_linear_list_issues"), names(turn));
            assertEquals(Map.of("Gmail", McpTurnTools.Unavailable.Reason.PERSONAL), reasons(turn));
        }
        // Nobody connects as the bot, so its personal connection is never looked up.
        verify(credentials, never()).findByTenantIdAndServerIdAndOwnerActorId(tenant, gmail.getId(), actor.value());
    }

    @Test
    void aPublicBotUsesOnlyServersOpenedToItAndOnlyTheirReadTools() {
        var linear = server("linear", "Linear", McpAuthType.NONE, McpAuthPerformer.ADMIN, 0);
        linear.configure("Linear", null, "http://10.0.0.5/mcp", McpAuthType.NONE, McpAuthPerformer.ADMIN, null, "[]", "{}",
                null, true, Set.of(), Set.of(), true, NOW);
        tools(linear, tool(linear, "list_issues", true, McpToolPolicy.ALWAYS),
                tool(linear, "create_issue", false, McpToolPolicy.ALWAYS));
        var crm = server("crm", "CRM", McpAuthType.NONE, McpAuthPerformer.ADMIN, 1);
        when(access.accessibleServerIds(tenant, actor.value())).thenReturn(Set.of(linear.getId(), crm.getId()));

        try (var turn = turns.open(actor, List.of(), List.of(linear.getId(), crm.getId()), Set.of(),
                McpTurnService.Surface.PUBLIC_BOT)) {
            assertEquals(List.of("mcp_linear_list_issues"), names(turn));
            assertEquals(Map.of("CRM", McpTurnTools.Unavailable.Reason.INACCESSIBLE), reasons(turn));
        }
    }

    @Test
    void aServerOpenThroughTheAssistantAloneIsOnForAnyoneWhoUsesItAndForNoOtherAssistant() {
        var linear = server("linear", "Linear", McpAuthType.NONE, McpAuthPerformer.ADMIN, 1);
        when(access.accessibleServerIds(tenant, actor.value())).thenReturn(Set.of());

        // Through its assistant: usable without access of one's own, and on without being selected.
        try (var turn = turns.open(actor, List.of(), List.of(linear.getId()), Set.of(linear.getId()), McpTurnService.Surface.WEB)) {
            assertEquals(List.of("mcp_linear_tool_0"), names(turn));
        }
        // Another assistant that still attaches it does not open it.
        try (var turn = turns.open(actor, List.of(), List.of(linear.getId()), Set.of(), McpTurnService.Surface.BOT_CHANNEL)) {
            assertEquals(List.of(), names(turn));
            assertEquals(Map.of("Linear", McpTurnTools.Unavailable.Reason.INACCESSIBLE), reasons(turn));
        }
    }

    private static List<String> names(McpTurnTools turn) {
        return turn.bindings().stream().map(McpTurnTools.Binding::modelName).sorted().toList();
    }

    private static Map<String, McpTurnTools.Unavailable.Reason> reasons(McpTurnTools turn) {
        return turn.unavailable().stream().collect(Collectors.toMap(McpTurnTools.Unavailable::serverName,
                McpTurnTools.Unavailable::reason));
    }

    private McpServerToolEntity tool(McpServerEntity server, String name, boolean readOnly, McpToolPolicy policy) {
        var tool = new McpServerToolEntity(UUID.randomUUID(), tenant, server.getId(), name);
        tool.snapshot(null, name, "{\"type\":\"object\"}", "{}", readOnly, NOW);
        tool.policy(policy, true);
        return tool;
    }

    private void tools(McpServerEntity server, McpServerToolEntity... listed) {
        when(tools.findByTenantIdAndServerIdOrderByNameAsc(tenant, server.getId())).thenReturn(List.of(listed));
    }

    private McpServerEntity server(String slug, String name, McpAuthType type, McpAuthPerformer performer, int enabledTools) {
        var server = new McpServerEntity(UUID.randomUUID(), tenant, slug, NOW);
        server.configure(name, null, "http://10.0.0.5/mcp", type, performer, null, "[]", "{}", null, true, Set.of(),
                Set.of(), false, NOW);
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
