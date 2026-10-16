import type { McpServerInput, McpServerView } from "@/lib/hey-api/types.gen";
import type { AppCopy } from "@/i18n/app-text";

/**
 * A known MCP server, prefilled into the add dialog. `oauthApp` says how its OAuth application comes about:
 * `AUTOMATIC` when the authorization server accepts MemoryOS's client metadata document or dynamic registration,
 * `MANUAL` when an administrator creates one in the provider's console (`setupUrl`) and pastes it.
 *
 * Each entry asks for the scopes its provider offers, so a tool the administrator turns on later needs no new
 * sign-in; what reaches the model is still decided per tool, and tools that may change data start off.
 * Probed 2026-10-14: every address answers an unauthenticated initialize with an OAuth challenge.
 */
export type McpCatalogEntry = {
  id: string;
  name: string;
  description: AppCopy;
  url: string;
  oauthApp: "AUTOMATIC" | "MANUAL";
  setupUrl?: string;
  scopes: string[];
  parameters: Record<string, string>;
};

/** Google issues a refresh token only with both, so a connection would otherwise end after an hour. */
const GOOGLE_PARAMETERS = { access_type: "offline", prompt: "consent" };
const GOOGLE_CONSOLE = "https://console.cloud.google.com/apis/credentials";

export const mcpCatalog: McpCatalogEntry[] = [
  {
    id: "drive",
    name: "Google Drive",
    description: "Tìm và đọc tệp trên Drive",
    url: "https://drivemcp.googleapis.com/mcp/v1",
    oauthApp: "MANUAL",
    setupUrl: GOOGLE_CONSOLE,
    scopes: ["https://www.googleapis.com/auth/drive"],
    parameters: GOOGLE_PARAMETERS,
  },
  {
    id: "gmail",
    name: "Gmail",
    description: "Tìm, đọc và soạn email",
    url: "https://gmailmcp.googleapis.com/mcp/v1",
    oauthApp: "MANUAL",
    setupUrl: GOOGLE_CONSOLE,
    scopes: [
      "https://www.googleapis.com/auth/gmail.readonly",
      "https://www.googleapis.com/auth/gmail.compose",
      "https://www.googleapis.com/auth/gmail.send",
      "https://www.googleapis.com/auth/gmail.modify",
      "https://www.googleapis.com/auth/gmail.labels",
    ],
    parameters: GOOGLE_PARAMETERS,
  },
  {
    id: "linear",
    name: "Linear",
    description: "Issue, dự án và chu kỳ",
    url: "https://mcp.linear.app/mcp",
    oauthApp: "AUTOMATIC",
    scopes: ["read", "write"],
    parameters: {},
  },
  {
    id: "notion",
    name: "Notion",
    description: "Trang, cơ sở dữ liệu và ghi chú",
    url: "https://mcp.notion.com/mcp",
    oauthApp: "AUTOMATIC",
    scopes: [],
    parameters: {},
  },
  {
    id: "github",
    name: "GitHub",
    description: "Repo, pull request và issue",
    url: "https://api.githubcopilot.com/mcp/",
    oauthApp: "MANUAL",
    setupUrl: "https://github.com/settings/developers",
    scopes: ["repo", "read:org", "read:user", "user:email", "project", "notifications"],
    parameters: {},
  },
  {
    id: "slack",
    name: "Slack",
    description: "Kênh, tin nhắn và tìm kiếm",
    url: "https://mcp.slack.com/mcp",
    oauthApp: "MANUAL",
    setupUrl: "https://api.slack.com/apps",
    scopes: [
      "channels:history",
      "channels:read",
      "groups:history",
      "groups:read",
      "im:history",
      "im:read",
      "mpim:history",
      "mpim:read",
      "chat:write",
      "files:read",
      "reactions:write",
      "search:read.public",
      "search:read.private",
      "search:read.files",
      "users:read",
      "users:read.email",
      "canvases:read",
      "canvases:write",
    ],
    parameters: {},
  },
  {
    id: "figma",
    name: "Figma",
    description: "Thiết kế và bình luận",
    url: "https://mcp.figma.com/mcp",
    oauthApp: "AUTOMATIC",
    scopes: ["mcp:connect"],
    parameters: {},
  },
  {
    id: "canva",
    name: "Canva",
    description: "Thiết kế và tài sản thương hiệu",
    // mcp.canva.com accepts only clients on Canva's allowlist; an app made in the Developer Portal, with Canva MCP
    // turned on there, connects here with the client ID and secret it issues.
    url: "https://api.canva.com/connect/v1/mcp",
    oauthApp: "MANUAL",
    setupUrl: "https://www.canva.com/developers/apps",
    scopes: [
      "profile:read",
      "design:meta:read",
      "design:content:read",
      "design:content:write",
      "folder:read",
      "folder:write",
      "brandtemplate:meta:read",
      "brandtemplate:content:read",
      "brandtemplate:content:write",
      "comment:read",
      "comment:write",
      "asset:read",
      "asset:write",
      "brandkit:read",
      "help:answers:read",
      "help:answers:write",
    ],
    parameters: {},
  },
];

function host(url: string): string {
  try {
    return new URL(url).hostname;
  } catch {
    return url;
  }
}

/** The catalog entry a server was added from, matched by its host. */
export function catalogEntryOf(url: string): McpCatalogEntry | undefined {
  return mcpCatalog.find((entry) => host(entry.url) === host(url));
}

/** Catalog entries not added yet. */
export function availableCatalog(servers: readonly McpServerView[]): McpCatalogEntry[] {
  const added = new Set(servers.map((server) => host(server.url)));
  return mcpCatalog.filter((entry) => !added.has(host(entry.url)));
}

/** The form values an entry fills in; the administrator still chooses who may use it. */
export type McpServerPreset = Pick<McpServerInput, "name" | "url" | "slug" | "oauthScopes"> & {
  oauthAdditionalParameters: Record<string, string>;
};

export function presetOf(entry: McpCatalogEntry): McpServerPreset {
  return {
    name: entry.name,
    url: entry.url,
    slug: entry.id,
    oauthScopes: entry.scopes,
    oauthAdditionalParameters: entry.parameters,
  };
}
