import type {
  GetGoogleDriveConfigurationResponse,
  GoogleDriveSelectionPolicyResponse,
} from "@/lib/hey-api/types.gen";

export const MAX_GOOGLE_DRIVE_LINK_LENGTH = 2048;

export function parseGoogleDriveLinks(value: string) {
  return value
    .split(/[\r\n,]+/)
    .map((link) => link.trim())
    .filter(Boolean);
}

export function googleDriveSelectionError(
  body: { scopeMode: "GENERAL" | "SPECIFIC"; links: string[]; linkedDocumentIds?: string[] },
  policy: GoogleDriveSelectionPolicyResponse | undefined,
) {
  if (!policy) return "Selection policy is unavailable. Refresh it before submitting.";
  if (body.scopeMode === "SPECIFIC" && body.links.length === 0)
    return "Supply at least one file or folder link.";
  if (body.links.length > policy.maxExplicitRootsPerSource)
    return `Use at most ${policy.maxExplicitRootsPerSource.toLocaleString()} explicit file or folder roots. Folder descendants do not consume this quota.`;
  if (body.links.some((link) => link.length > MAX_GOOGLE_DRIVE_LINK_LENGTH))
    return "Each link must be no longer than 2,048 characters.";
  if ((body.linkedDocumentIds?.length ?? 0) > policy.maxLinkedDocuments)
    return `Approve at most ${policy.maxLinkedDocuments.toLocaleString()} linked documents.`;
  if (new TextEncoder().encode(JSON.stringify(body)).byteLength > policy.maxRequestBytes)
    return `The selection request exceeds the server's ${policy.maxRequestBytes.toLocaleString()}-byte limit. Reduce the submitted links.`;
  return null;
}

export function reconcileGoogleDriveConfiguration(
  current: GetGoogleDriveConfigurationResponse | undefined,
  incoming: GetGoogleDriveConfigurationResponse,
) {
  if (!current) return incoming;
  const scope =
    current.revision > incoming.revision || current.credentialRevision > incoming.credentialRevision
      ? current
      : incoming;
  const sameAuthority =
    current.revision === incoming.revision &&
    current.credentialId === incoming.credentialId &&
    current.credentialRevision === incoming.credentialRevision;
  const discovery =
    sameAuthority && current.discoveryRevision > incoming.discoveryRevision ? current : scope;
  const schedule = current.scheduleRevision > incoming.scheduleRevision ? current : incoming;
  return {
    ...scope,
    discoveryRevision: discovery.discoveryRevision,
    discoveredAt: discovery.discoveredAt,
    discoveryErrors: discovery.discoveryErrors,
    counts: discovery.counts,
    syncIntervalMinutes: schedule.syncIntervalMinutes,
    scheduleRevision: schedule.scheduleRevision,
  };
}
