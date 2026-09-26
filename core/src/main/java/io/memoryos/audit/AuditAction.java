package io.memoryos.audit;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * What an audit event records. Values are an append-only contract, as Onyx's {@code AuditAction} is: a recorded action
 * keeps its meaning forever, so an investigation into last quarter reads the same way as one into today. Adding an
 * action is a normal change; changing or removing one is not.
 *
 * <p>Each action declares the fields its {@code details} may carry. Onyx passes a free-form {@code extra} map and
 * relies on every call site to keep secrets out of it; here a field the action did not declare is rejected, so a
 * mistake at one call site cannot put a provider key into the record.
 */
public enum AuditAction {
    // Authentication.
    LOGIN("auth.login", AuditEventClass.AUTHENTICATION),
    LOGIN_FAILURE("auth.login_failure", AuditEventClass.AUTHENTICATION, "reason"),
    LOGOUT("auth.logout", AuditEventClass.AUTHENTICATION, "providerSessionEnded"),
    JIT_ADMIT("auth.jit_admit", AuditEventClass.AUTHENTICATION, "issuer"),

    // Account lifecycle.
    USER_INVITE("user.invite", AuditEventClass.ACCOUNT_CHANGE, "email", "expiresAt"),
    USER_INVITE_ROTATE("user.invite_rotate", AuditEventClass.ACCOUNT_CHANGE, "email", "expiresAt"),
    USER_INVITE_REVOKE("user.invite_revoke", AuditEventClass.ACCOUNT_CHANGE, "email"),
    USER_JOIN("user.join", AuditEventClass.ACCOUNT_CHANGE, "email", "admission"),
    USER_DEACTIVATE("user.deactivate", AuditEventClass.ACCOUNT_CHANGE, "email"),
    USER_REACTIVATE("user.reactivate", AuditEventClass.ACCOUNT_CHANGE, "email"),

    // Who holds which authority.
    USER_GROUP_CHANGE("user.group_change", AuditEventClass.USER_ACCESS_MANAGEMENT, "email", "added", "removed"),

    // Group lifecycle.
    GROUP_CREATE("user_group.create", AuditEventClass.GROUP_MANAGEMENT),
    GROUP_RENAME("user_group.rename", AuditEventClass.GROUP_MANAGEMENT, "before", "after"),
    GROUP_DELETE("user_group.delete", AuditEventClass.GROUP_MANAGEMENT, "memberCount"),
    GROUP_MEMBER_CHANGE("user_group.member_change", AuditEventClass.GROUP_MANAGEMENT, "added", "removed"),
    GROUP_MANAGER_CHANGE("user_group.manager_change", AuditEventClass.GROUP_MANAGEMENT, "member", "email", "manager"),
    GROUP_PERMISSION_CHANGE("user_group.permission_change", AuditEventClass.GROUP_MANAGEMENT, "before", "after"),

    // AI configuration.
    PROVIDER_CREATE("llm_provider.create", AuditEventClass.API_ACTIVITY, "adapter", "dataBoundary"),
    PROVIDER_UPDATE("llm_provider.update", AuditEventClass.API_ACTIVITY, "before", "after", "credentialChange"),
    PROVIDER_DELETE("llm_provider.delete", AuditEventClass.API_ACTIVITY, "adapter"),
    MODEL_CREATE("model.create", AuditEventClass.API_ACTIVITY, "provider"),
    MODEL_UPDATE("model.update", AuditEventClass.API_ACTIVITY, "provider"),
    MODEL_DELETE("model.delete", AuditEventClass.API_ACTIVITY, "provider"),
    MODEL_DEFAULT_CHANGE("model_default.change", AuditEventClass.API_ACTIVITY, "before", "after"),
    MODEL_FLOW_CHANGE("model_flow.change", AuditEventClass.API_ACTIVITY, "flow", "before", "after"),
    WEB_CONNECTION_CHANGE("web_connection.change", AuditEventClass.API_ACTIVITY, "change", "credentialChange"),
    VOICE_CONNECTION_CHANGE("voice_connection.change", AuditEventClass.API_ACTIVITY, "change", "credentialChange"),
    IMAGE_CONNECTION_CHANGE("image_connection.change", AuditEventClass.API_ACTIVITY, "change", "credentialChange"),
    INTERPRETER_CHANGE("interpreter.change", AuditEventClass.API_ACTIVITY, "enabled"),
    CHAT_SETTINGS_CHANGE("chat_settings.change", AuditEventClass.API_ACTIVITY, "deepResearchEnabled", "chatHistoryVisibility"),
    MCP_SERVER_CREATE("mcp_server.create", AuditEventClass.API_ACTIVITY, "after"),
    MCP_SERVER_UPDATE("mcp_server.update", AuditEventClass.API_ACTIVITY, "before", "after", "credentialChange"),
    MCP_SERVER_DELETE("mcp_server.delete", AuditEventClass.API_ACTIVITY, "url"),
    MCP_TOOL_CHANGE("mcp_tool.change", AuditEventClass.API_ACTIVITY, "tools", "enabled"),
    MCP_OAUTH_CLIENT_CHANGE("mcp_oauth_client.change", AuditEventClass.API_ACTIVITY, "change", "client", "issuer"),
    MCP_CONNECTION_CHANGE("mcp_connection.change", AuditEventClass.API_ACTIVITY, "change"),
    IDENTITY_PROVIDER_CREATE("identity_provider.create", AuditEventClass.API_ACTIVITY, "after"),
    IDENTITY_PROVIDER_UPDATE("identity_provider.update", AuditEventClass.API_ACTIVITY, "before", "after"),
    IDENTITY_PROVIDER_DELETE("identity_provider.delete", AuditEventClass.API_ACTIVITY, "alias"),

    // Sources and the credentials that reach them.
    SOURCE_CREATE("source.create", AuditEventClass.API_ACTIVITY, "provider", "access", "groups"),
    SOURCE_UPDATE("source.update", AuditEventClass.API_ACTIVITY, "change", "before", "after"),
    SOURCE_DELETE("source.delete", AuditEventClass.API_ACTIVITY, "provider"),
    SOURCE_ACCESS_CHANGE("source.access_change", AuditEventClass.API_ACTIVITY, "before", "after"),
    SOURCE_MANAGER_CHANGE("source.manager_change", AuditEventClass.API_ACTIVITY, "before", "after"),
    SOURCE_GROUP_CHANGE("source.group_change", AuditEventClass.API_ACTIVITY, "added", "removed"),
    SOURCE_PAUSE("source.pause", AuditEventClass.API_ACTIVITY),
    SOURCE_RESUME("source.resume", AuditEventClass.API_ACTIVITY),
    SOURCE_ITEM_REMOVE("source.item_remove", AuditEventClass.API_ACTIVITY, "item"),
    CREDENTIAL_CREATE("credential.create", AuditEventClass.API_ACTIVITY, "provider", "authentication"),
    CREDENTIAL_UPDATE("credential.update", AuditEventClass.API_ACTIVITY, "provider", "change"),
    CREDENTIAL_DELETE("credential.delete", AuditEventClass.API_ACTIVITY, "provider"),

    // What a Tenant may spend on AI (MEM-123).
    AI_LIMIT_CREATE("ai_limit.create", AuditEventClass.API_ACTIVITY, "scope", "group", "after"),
    AI_LIMIT_UPDATE("ai_limit.update", AuditEventClass.API_ACTIVITY, "scope", "group", "before", "after"),
    AI_LIMIT_DELETE("ai_limit.delete", AuditEventClass.API_ACTIVITY, "scope", "group", "before"),

    // Reading other people's questions (MEM-125); Onyx records nothing here.
    CHAT_HISTORY_READ("chat_history.read", AuditEventClass.API_ACTIVITY, "person", "email", "messages"),
    CHAT_HISTORY_EXPORT("chat_history.export", AuditEventClass.API_ACTIVITY, "from", "to", "rows"),

    // The audit stream's own reads that leave the system, and refused authority.
    AUDIT_EXPORT("audit.export", AuditEventClass.API_ACTIVITY, "from", "to", "rows"),
    PERMISSION_DENIED("permission.denied", AuditEventClass.API_ACTIVITY, "capability", "scope");

    private static final Map<String, AuditAction> BY_VALUE = buildIndex();

    private final String value;
    private final AuditEventClass eventClass;
    private final Set<String> fields;

    AuditAction(String value, AuditEventClass eventClass, String... fields) {
        this.value = value;
        this.eventClass = eventClass;
        this.fields = Set.of(fields);
    }

    public String value() { return value; }

    public AuditEventClass eventClass() { return eventClass; }

    /** The only keys this action's {@code details} may carry. */
    public Set<String> fields() { return fields; }

    /** A stored event's action, or empty when a row predates it; a reader never fails over an unknown value. */
    public static Optional<AuditAction> of(String value) {
        return Optional.ofNullable(BY_VALUE.get(value));
    }

    private static Map<String, AuditAction> buildIndex() {
        var index = new HashMap<String, AuditAction>();
        for (AuditAction action : values()) {
            if (index.put(action.value, action) != null)
                throw new IllegalStateException("Duplicate audit action " + action.value);
        }
        return Map.copyOf(index);
    }
}
