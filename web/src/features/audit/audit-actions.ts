import type { AppCopy } from "@/i18n/app-text";
import type { AuditEvent } from "@/lib/hey-api/types.gen";

/**
 * A readable sentence for each recorded action, shown beside its code as PlanetScale and Okta show theirs. The codes
 * are an append-only contract (ADR 0013); an action this build does not know still shows its code.
 */
const actionLabels: Record<string, AppCopy> = {
  "auth.login": "Signed in",
  "auth.login_failure": "Sign-in refused",
  "auth.logout": "Signed out",
  "auth.jit_admit": "Joined through single sign-on",
  "user.invite": "Invited a user",
  "user.invite_rotate": "Renewed an invitation link",
  "user.invite_revoke": "Revoked an invitation",
  "user.join": "Accepted an invitation",
  "user.deactivate": "Deactivated a user",
  "user.reactivate": "Reactivated a user",
  "user.group_change": "Changed a user's groups",
  "user_group.create": "Created a group",
  "user_group.rename": "Renamed a group",
  "user_group.delete": "Deleted a group",
  "user_group.member_change": "Changed group members",
  "user_group.manager_change": "Changed a group manager",
  "user_group.permission_change": "Changed group permissions",
  "llm_provider.create": "Added a model provider",
  "llm_provider.update": "Updated a model provider",
  "llm_provider.delete": "Deleted a model provider",
  "model.create": "Added a model",
  "model.update": "Updated a model",
  "model.delete": "Deleted a model",
  "model_default.change": "Changed the default model",
  "model_flow.change": "Changed a task model",
  "web_connection.change": "Changed Web search",
  "voice_connection.change": "Changed voice",
  "image_connection.change": "Changed image generation",
  "interpreter.change": "Changed Code Interpreter",
  "chat_settings.change": "Changed Chat settings",
  "mcp_server.create": "Added an MCP server",
  "mcp_server.update": "Updated an MCP server",
  "mcp_server.delete": "Deleted an MCP server",
  "mcp_tool.change": "Changed MCP tools",
  "mcp_oauth_client.change": "Changed an MCP OAuth client",
  "mcp_connection.change": "Changed an MCP shared connection",
  "identity_provider.create": "Added a sign-in provider",
  "identity_provider.update": "Updated a sign-in provider",
  "identity_provider.delete": "Deleted a sign-in provider",
  "source.create": "Added a source",
  "source.update": "Updated a source",
  "source.delete": "Deleted a source",
  "source.access_change": "Changed source visibility",
  "source.manager_change": "Changed a source manager",
  "source.group_change": "Changed source groups",
  "source.pause": "Paused a source",
  "source.resume": "Resumed a source",
  "source.item_remove": "Removed a document from a source",
  "credential.create": "Added a credential",
  "credential.update": "Updated a credential",
  "credential.delete": "Deleted a credential",
  "audit.export": "Exported the audit log",
  "permission.denied": "Access refused",
};

export const classLabels: Record<AuditEvent["eventClass"], AppCopy> = {
  AUTHENTICATION: "Sign-in",
  ACCOUNT_CHANGE: "Accounts",
  USER_ACCESS_MANAGEMENT: "Access",
  GROUP_MANAGEMENT: "Groups",
  API_ACTIVITY: "Configuration",
};

export const outcomeLabels: Record<AuditEvent["outcome"], AppCopy> = {
  SUCCESS: "Succeeded",
  FAILURE: "Failed",
  DENIED: "Denied",
};

export const outcomeTones = { SUCCESS: "success", FAILURE: "warning", DENIED: "danger" } as const;

/** Periods as the AI costs page offers them, counted back from now. */
export const periodDays = { "1d": 1, "7d": 7, "30d": 30, "90d": 90 } as const;
export type AuditPeriod = keyof typeof periodDays;
export const periodLabels: Record<AuditPeriod, AppCopy> = {
  "1d": "Last 24 hours",
  "7d": "Last 7 days",
  "30d": "Last 30 days",
  "90d": "Last 90 days",
};

export function periodStart(period: AuditPeriod, now = Date.now()) {
  return new Date(now - periodDays[period] * 86_400_000).toISOString();
}

/** Readable names for the declared detail fields; a field this build does not know shows its name. */
const fieldLabels: Record<string, AppCopy> = {
  adapter: "Adapter",
  added: "Added",
  admission: "Admission",
  alias: "Alias",
  authentication: "Authentication",
  baseUrl: "Endpoint",
  capability: "Permission",
  change: "Change",
  client: "Client",
  credentialChange: "Credential",
  dataBoundary: "Data boundary",
  deepResearchEnabled: "Deep research",
  email: "Email",
  enabled: "Enabled",
  expiresAt: "Expires",
  flow: "Task",
  groups: "Groups",
  issuer: "Issuer",
  item: "Document",
  jitAllowed: "Join on first sign-in",
  manager: "Manager",
  member: "Member",
  memberCount: "Members",
  model: "Model",
  name: "Name",
  performer: "Connected by",
  provider: "Provider",
  providerSessionEnded: "Provider session ended",
  public: "Available to everyone",
  reason: "Reason",
  removed: "Removed",
  rows: "Rows",
  scope: "Scope",
  tenantWide: "Available to everyone",
  tools: "Tools",
  url: "Endpoint",
};

/** Recorded values that are codes, shown as words; data such as names and models is shown as recorded. */
const valueLabels: Record<string, AppCopy> = {
  INTERNAL: "Internal",
  EXTERNAL: "External",
  KEEP: "Kept",
  REPLACE: "Replaced",
  REMOVE: "Removed",
  NOT_ADMITTED: "Not admitted",
  PUBLIC: "Public",
  PRIVATE: "Private",
  SYNC: "Auto Sync",
};

type Translate = (copy: AppCopy) => string;

export function fieldLabel(field: string, ui: Translate) {
  const label = fieldLabels[field];
  return label ? ui(label) : field;
}

/** The action's name, or its code when this client has no name for it. */
export function actionLabel(action: string, ui: Translate) {
  const label = actionLabels[action];
  return label ? ui(label) : action;
}

/** A detail value as text: lists joined, objects as their fields, codes as words, nothing for an absent value. */
export function detailText(value: unknown, ui: Translate = (copy) => String(copy)): string {
  if (value === null || value === undefined) return "—";
  if (Array.isArray(value))
    return value.length === 0 ? "—" : value.map((item) => detailText(item, ui)).join(", ");
  if (typeof value === "object")
    return Object.entries(value as Record<string, unknown>)
      .map(([key, field]) => `${fieldLabel(key, ui)}: ${detailText(field, ui)}`)
      .join(" · ");
  if (typeof value === "boolean") return ui(value ? "Yes" : "No");
  const text = String(value);
  const label = valueLabels[text];
  return label ? ui(label) : text;
}

/**
 * The before-and-after rows of a changed setting: one row per field that differs, as Employment Hero and Mixpanel show
 * a change. A value that is not an object is one row.
 */
export function changeRows(
  before: unknown,
  after: unknown,
  ui: Translate = (copy) => String(copy),
): { field: string; before: string; after: string }[] {
  const isObject = (value: unknown) =>
    value !== null && typeof value === "object" && !Array.isArray(value);
  if (!isObject(before) && !isObject(after))
    return [{ field: "", before: detailText(before, ui), after: detailText(after, ui) }];
  const left = (isObject(before) ? before : {}) as Record<string, unknown>;
  const right = (isObject(after) ? after : {}) as Record<string, unknown>;
  const fields = [...new Set([...Object.keys(left), ...Object.keys(right)])];
  return fields
    .map((field) => ({
      field,
      before: detailText(left[field], ui),
      after: detailText(right[field], ui),
    }))
    .filter((row) => row.before !== row.after);
}
