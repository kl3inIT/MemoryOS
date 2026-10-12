import { appText, type AppCopy } from "@/i18n/app-text";
import type { LarkRootRequest, LarkScopeRequest } from "@/lib/hey-api/types.gen";
import {
  splitSyncInterval,
  syncIntervalMinutes,
  type SyncIntervalUnit,
} from "@/features/sources/shared/sync-interval";

/** The connector accepts at most this many spaces and folders, and an interval of at most a year. */
const MAX_LARK_ROOTS = 100;
const MAX_LARK_SYNC_INTERVAL_MINUTES = 525_600;
const DEFAULT_LARK_SYNC_INTERVAL_MINUTES = 60;
const MAX_LARK_LINK_LENGTH = 2048;

const TOKEN = /^[A-Za-z0-9_-]{8,128}$/;
const FOLDER_PATH = "/drive/folder/";

export type LarkRegion = "LARK" | "FEISHU";

export type LarkScopeDraft = {
  /** Wiki spaces, by the ID Lark lists them under. */
  spaceIds: readonly string[];
  /** Drive folder links or tokens, one per line. */
  folderLinksText: string;
  interval: { value: string; unit: SyncIntervalUnit };
};

export function emptyLarkScopeDraft(): LarkScopeDraft {
  return {
    spaceIds: [],
    folderLinksText: "",
    interval: larkInterval(DEFAULT_LARK_SYNC_INTERVAL_MINUTES),
  };
}

export function larkInterval(minutes: number): LarkScopeDraft["interval"] {
  const { value, unit } = splitSyncInterval(minutes);
  return { value: String(value), unit };
}

/** Minutes for the drafted interval, or null unless the connector accepts it. */
export function larkIntervalMinutes(interval: LarkScopeDraft["interval"]) {
  const minutes = syncIntervalMinutes(interval.value, interval.unit);
  return minutes !== null && minutes <= MAX_LARK_SYNC_INTERVAL_MINUTES ? minutes : null;
}

export function parseLarkLines(text: string) {
  return text
    .split(/\r?\n/)
    .map((line) => line.trim())
    .filter(Boolean);
}

/**
 * The token of a Drive folder, from its address bar link or from the token itself. Mirrors the
 * connector's rule, so a mistyped line is reported before the request is sent; Lark still decides
 * whether the app can read the folder.
 */
export function larkFolderToken(value: string, region: LarkRegion): string | null {
  const trimmed = value.trim();
  if (TOKEN.test(trimmed)) return trimmed;
  if (trimmed.length > MAX_LARK_LINK_LENGTH) return null;
  let url: URL;
  try {
    url = new URL(trimmed);
  } catch {
    return null;
  }
  const host = url.hostname.toLowerCase();
  const domain = region === "FEISHU" ? "feishu.cn" : "larksuite.com";
  if (url.protocol !== "https:" || url.username || url.password) return null;
  if (host !== domain && !host.endsWith(`.${domain}`)) return null;
  if (!url.pathname.startsWith(FOLDER_PATH)) return null;
  const token = url.pathname.slice(FOLDER_PATH.length).replace(/\/+$/, "");
  return TOKEN.test(token) ? token : null;
}

export function larkScopeError(draft: LarkScopeDraft, region: LarkRegion): AppCopy | null {
  const lines = parseLarkLines(draft.folderLinksText);
  const invalid = lines.find((line) => larkFolderToken(line, region) === null);
  if (invalid)
    return appText("“{{v1}}” is not a Drive folder link on {{v2}}.", {
      v1: invalid.length > 80 ? `${invalid.slice(0, 80)}…` : invalid,
      v2: region === "FEISHU" ? "feishu.cn" : "larksuite.com",
    });
  const count =
    draft.spaceIds.length + new Set(lines.map((line) => larkFolderToken(line, region))).size;
  if (count === 0) return "Choose at least one Wiki space or Drive folder.";
  if (count > MAX_LARK_ROOTS)
    return appText("Choose at most {{v1}} spaces and folders.", { v1: MAX_LARK_ROOTS });
  if (larkIntervalMinutes(draft.interval) === null)
    return "Choose a synchronization interval between one minute and one year.";
  return null;
}

/** The request for a draft that passed `larkScopeError`; folders are sent once each, by token. */
export function larkScopeRequest(draft: LarkScopeDraft, region: LarkRegion): LarkScopeRequest {
  const folders = new Set<string>();
  for (const line of parseLarkLines(draft.folderLinksText)) {
    const token = larkFolderToken(line, region);
    if (token) folders.add(token);
  }
  const roots: LarkRootRequest[] = [
    ...draft.spaceIds.map((value) => ({ kind: "WIKI_SPACE" as const, value })),
    ...[...folders].map((value) => ({ kind: "DRIVE_FOLDER" as const, value })),
  ];
  return {
    roots,
    syncIntervalMinutes: larkIntervalMinutes(draft.interval) ?? DEFAULT_LARK_SYNC_INTERVAL_MINUTES,
  };
}

/** A credential's region as the API reports it, defaulting to Lark international. */
export function larkRegion(region: string | undefined): LarkRegion {
  return region === "FEISHU" ? "FEISHU" : "LARK";
}
