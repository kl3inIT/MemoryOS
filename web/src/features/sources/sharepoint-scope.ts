import { appText, type AppCopy } from "@/i18n/app-text";
import type {
  SharePointScopeRequest,
  SharePointSelectionPolicyResponse,
} from "@/lib/hey-api/types.gen";

export const MAX_SHAREPOINT_URL_LENGTH = 2048;
export const MAX_SHAREPOINT_EXCLUSION_LENGTH = 512;
export const DEFAULT_SHAREPOINT_SYNC_INTERVAL_MINUTES = 30;
export const DEFAULT_SHAREPOINT_PRUNE_INTERVAL_HOURS = 168;
export const MAX_SHAREPOINT_SYNC_INTERVAL_MINUTES = 2_147_483_647;
export const MAX_SHAREPOINT_PRUNE_INTERVAL_HOURS = 8_760;

const HOST = /^[a-z0-9]([a-z0-9-]{0,62}[a-z0-9])?(-my)?\.sharepoint\.com$/;
const SHARE_LINK = /^:[a-z]:$/;
const SITE_PREFIXES = ["sites", "teams", "personal"];
const MAX_SEGMENTS = 64;

export type SharePointAddress = {
  kind: "SITE" | "LIBRARY" | "FOLDER";
  host: string;
  sitePath: string;
  librarySegment?: string;
  folderSegments: string[];
  path: string;
};

export type SharePointAddressResult = { address: SharePointAddress } | { error: AppCopy };

/**
 * Mirrors the address rules the connector applies, so a mistyped address is reported on its own
 * line before the request is sent. Microsoft still decides whether an address resolves.
 */
export function parseSharePointAddress(value: string): SharePointAddressResult {
  const trimmed = value.trim();
  if (!trimmed || trimmed.length > MAX_SHAREPOINT_URL_LENGTH)
    return { error: "Paste a SharePoint site, library or folder address." };
  let url: URL;
  try {
    url = new URL(trimmed);
  } catch {
    return { error: "That is not a valid address." };
  }
  if (url.protocol !== "https:" || url.username || url.password || !url.hostname)
    return { error: "The address must start with https:// and name a SharePoint host." };
  const host = url.hostname.toLowerCase();
  if (!HOST.test(host))
    return { error: "The address must be on your organization's sharepoint.com host." };
  let segments: string[] = [];
  for (const raw of url.pathname.split("/")) {
    if (!raw) continue;
    if (segments.length >= MAX_SEGMENTS) return { error: "That address is nested too deeply." };
    let decoded: string;
    try {
      decoded = decodeURIComponent(raw);
    } catch {
      return { error: "That address contains an unusable path segment." };
    }
    if (!decoded.trim() || decoded.includes("\\") || decoded.length > 255)
      return { error: "That address contains an unusable path segment." };
    segments.push(decoded);
  }
  // A sharing link keeps the real path after its ":f:/r" style prefix.
  if (segments.length > 0 && SHARE_LINK.test(segments[0])) segments = segments.slice(2);
  if (segments.length < 2 || !SITE_PREFIXES.includes(segments[0].toLowerCase()))
    return { error: "The address must contain /sites/, /teams/ or /personal/." };
  const sitePath = `/${segments[0].toLowerCase()}/${segments[1]}`;
  const rest = segments.slice(2);
  if (rest.length === 0)
    return { address: { kind: "SITE", host, sitePath, folderSegments: [], path: sitePath } };
  // "Forms" holds a library's views, not its content, so a view address means the library itself.
  const folders = rest[1]?.toLowerCase() === "forms" ? [] : rest.slice(1);
  const librarySegment = rest[0];
  return {
    address: {
      kind: folders.length === 0 ? "LIBRARY" : "FOLDER",
      host,
      sitePath,
      librarySegment,
      folderSegments: folders,
      path: [sitePath, librarySegment, ...folders].join("/"),
    },
  };
}

/** Whether `root` already covers `other`, so both must not be selected at once. */
export function coversSharePointAddress(root: SharePointAddress, other: SharePointAddress) {
  if (root.host !== other.host || !equalsIgnoreCase(root.sitePath, other.sitePath)) return false;
  const sameLibrary =
    Boolean(other.librarySegment) && equalsIgnoreCase(root.librarySegment, other.librarySegment);
  if (root.kind === "SITE") return true;
  if (root.kind === "LIBRARY") return sameLibrary;
  return (
    sameLibrary &&
    other.kind === "FOLDER" &&
    root.folderSegments.length <= other.folderSegments.length &&
    root.folderSegments.every((segment, index) =>
      equalsIgnoreCase(segment, other.folderSegments[index]),
    )
  );
}

export function parseSharePointLines(value: string) {
  return value
    .split(/\r?\n/)
    .map((line) => line.trim())
    .filter(Boolean);
}

export type SharePointLineProblem = { line: number; value: string; message: AppCopy };

/** One problem per pasted line, so an administrator sees which address to fix. */
export function sharePointAddressProblems(value: string): SharePointLineProblem[] {
  const problems: SharePointLineProblem[] = [];
  const accepted: SharePointAddress[] = [];
  let line = 0;
  for (const raw of value.split(/\r?\n/)) {
    line += 1;
    const entry = raw.trim();
    if (!entry) continue;
    const result = parseSharePointAddress(entry);
    if ("error" in result) {
      problems.push({ line, value: entry, message: result.error });
      continue;
    }
    const address = result.address;
    if (accepted.some((other) => other.path === address.path && other.host === address.host)) {
      problems.push({ line, value: entry, message: "This address is already listed above." });
      continue;
    }
    if (accepted.some((other) => other.host !== address.host)) {
      problems.push({
        line,
        value: entry,
        message: "Every address must be on the same SharePoint host.",
      });
      continue;
    }
    if (
      accepted.some(
        (other) =>
          coversSharePointAddress(other, address) || coversSharePointAddress(address, other),
      )
    ) {
      problems.push({
        line,
        value: entry,
        message: "Select either a site, library or folder, not one inside another.",
      });
      continue;
    }
    accepted.push(address);
  }
  return problems;
}

export function sharePointExclusionProblems(value: string): SharePointLineProblem[] {
  const problems: SharePointLineProblem[] = [];
  let line = 0;
  for (const raw of value.split(/\r?\n/)) {
    line += 1;
    const entry = raw.trim();
    if (!entry) continue;
    if (entry.length > MAX_SHAREPOINT_EXCLUSION_LENGTH)
      problems.push({
        line,
        value: entry,
        message: "Each exclusion must contain 1 to 512 characters.",
      });
  }
  return problems;
}

export type SharePointScopeDraft = {
  scopeMode: SharePointScopeRequest["scopeMode"];
  siteUrlsText: string;
  excludedSitesText: string;
  excludedPathsText: string;
  includeDocuments: boolean;
  includePages: boolean;
  syncIntervalMinutes: string;
  pruneIntervalHours: string;
};

export function emptySharePointScopeDraft(): SharePointScopeDraft {
  return {
    scopeMode: "SPECIFIC",
    siteUrlsText: "",
    excludedSitesText: "",
    excludedPathsText: "",
    includeDocuments: true,
    includePages: false,
    syncIntervalMinutes: String(DEFAULT_SHAREPOINT_SYNC_INTERVAL_MINUTES),
    pruneIntervalHours: String(DEFAULT_SHAREPOINT_PRUNE_INTERVAL_HOURS),
  };
}

export function sharePointScopeRequest(draft: SharePointScopeDraft): SharePointScopeRequest {
  const siteUrls = draft.scopeMode === "ALL_SITES" ? [] : parseSharePointLines(draft.siteUrlsText);
  const excludedSites = parseSharePointLines(draft.excludedSitesText);
  const excludedPaths = parseSharePointLines(draft.excludedPathsText);
  return {
    scopeMode: draft.scopeMode,
    ...(siteUrls.length > 0 ? { siteUrls } : {}),
    ...(excludedSites.length > 0 ? { excludedSites } : {}),
    ...(excludedPaths.length > 0 ? { excludedPaths } : {}),
    includeDocuments: draft.includeDocuments,
    includePages: draft.includePages,
    syncIntervalMinutes: Number(draft.syncIntervalMinutes),
    pruneIntervalHours: Number(draft.pruneIntervalHours),
  };
}

export function sharePointScheduleError(
  draft: Pick<SharePointScopeDraft, "syncIntervalMinutes" | "pruneIntervalHours">,
): AppCopy | null {
  const interval = Number(draft.syncIntervalMinutes);
  if (
    !Number.isInteger(interval) ||
    interval < 1 ||
    interval > MAX_SHAREPOINT_SYNC_INTERVAL_MINUTES
  )
    return "Enter a whole number of minutes from 1 to 2147483647.";
  const prune = Number(draft.pruneIntervalHours);
  if (!Number.isInteger(prune) || prune < 0 || prune > MAX_SHAREPOINT_PRUNE_INTERVAL_HOURS)
    return "Enter whole hours from 0 to 8760; 0 disables pruning.";
  return null;
}

export function sharePointScopeError(
  draft: SharePointScopeDraft,
  policy: SharePointSelectionPolicyResponse | undefined,
  body?: unknown,
): AppCopy | null {
  if (!policy) return "Selection limits are unavailable. Refresh them before submitting.";
  const roots = draft.scopeMode === "ALL_SITES" ? [] : parseSharePointLines(draft.siteUrlsText);
  if (draft.scopeMode === "SPECIFIC" && roots.length === 0)
    return "Paste at least one site, library or folder address.";
  if (roots.length > policy.maxRootsPerSource)
    return appText("Select at most {{count}} sites, libraries or folders.", {
      count: policy.maxRootsPerSource,
    });
  if (sharePointAddressProblems(draft.siteUrlsText).length > 0)
    return "Fix the addresses marked below before continuing.";
  for (const text of [draft.excludedSitesText, draft.excludedPathsText]) {
    if (parseSharePointLines(text).length > policy.maxExclusionsPerKind)
      return appText("Use at most {{count}} exclusions of each kind.", {
        count: policy.maxExclusionsPerKind,
      });
    if (sharePointExclusionProblems(text).length > 0)
      return "Each exclusion must contain 1 to 512 characters.";
  }
  if (!draft.includeDocuments && !draft.includePages)
    return "Collect documents, site pages, or both.";
  const schedule = sharePointScheduleError(draft);
  if (schedule) return schedule;
  if (body && new TextEncoder().encode(JSON.stringify(body)).byteLength > policy.maxRequestBytes)
    return appText("The request exceeds the server's {{count}}-byte limit. Paste fewer addresses.", {
      count: policy.maxRequestBytes,
    });
  return null;
}

function equalsIgnoreCase(left: string | undefined, right: string | undefined) {
  return (left ?? "").toLowerCase() === (right ?? "").toLowerCase();
}
