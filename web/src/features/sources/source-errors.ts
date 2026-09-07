import { ApiError } from "@/lib/api";

type SourceMutation =
  | "create"
  | "upload"
  | "reindex"
  | "remove-item"
  | "delete-source"
  | "google-drive"
  | "google-drive-schedule";

const statusMessages: Record<string, string> = {
  OBJECT_UPLOAD_INTEGRITY_MISMATCH:
    "Object storage did not receive the declared file. Start the upload again.",
  OBJECT_UPLOAD_STORAGE_UNAVAILABLE: "Object storage is temporarily unavailable. Retry the upload.",
  SOURCE_TENANT_INACTIVE:
    "Processing paused because this Tenant is inactive. Contact an administrator.",
  SOURCE_EXTRACTION_UNSUPPORTED: "This file type could not be extracted.",
  SOURCE_EXTRACTION_ENCRYPTED: "Password-protected files cannot be indexed.",
  SOURCE_EXTRACTION_MALFORMED: "The file could not be read. Check the file and upload it again.",
  SOURCE_EXTRACTION_TIMEOUT: "File extraction took too long. Try indexing the file again.",
  SOURCE_EXTRACTION_WRITE_LIMIT: "The extracted document exceeds the supported text limit.",
  SOURCE_EXTRACTION_INTERNAL: "File extraction failed unexpectedly. Try indexing the file again.",
  SOURCE_CLEANUP_INTERNAL: "Cleanup failed unexpectedly. Try the removal again.",
  SOURCE_GOOGLE_REVISION_CONFLICT:
    "This configuration changed in another session. Reload the saved selection before trying again.",
  SOURCE_GOOGLE_ROOTS_OVERLAP:
    "Some supplied links overlap. Use a folder or its descendants, not both.",
  SOURCE_GOOGLE_ROOT_UNSUPPORTED:
    "This selection includes an unsupported item. Shortcuts and trashed items cannot be selected.",
  SOURCE_GOOGLE_ROOT_LINK_INVALID:
    "Paste 1–20 unique HTTPS Google file or folder links. Whole-account links, duplicate files, and invalid URLs cannot be selected.",
  GOOGLE_DRIVE_NOT_CONFIGURED:
    "Google Drive is not configured on this server. Contact an administrator.",
  GOOGLE_DRIVE_OAUTH_CLIENT_REQUIRED:
    "This connection needs your Google OAuth app. Upload or paste a Web application OAuth client JSON, then reconnect.",
  GOOGLE_DRIVE_OAUTH_CLIENT_INVALID:
    "Supply valid Google Web application OAuth client JSON with this MemoryOS callback registered as an authorized redirect URI.",
  GOOGLE_DRIVE_AUTHENTICATION:
    "Google could not authorize this request. Reconnect the Google account.",
  GOOGLE_DRIVE_UNAVAILABLE: "Google Drive is temporarily unavailable. Try again later.",
  GOOGLE_DRIVE_QUOTA: "Google Drive is limiting requests. Wait before trying again.",
  GOOGLE_DRIVE_UNSUPPORTED:
    "This Google Drive item is not supported. Shortcuts cannot be imported.",
  GOOGLE_DRIVE_LIMIT_EXCEEDED:
    "This request exceeds the supported Google Drive acquisition limits.",
  SOURCE_GOOGLE_AUTHENTICATION: "Synchronization paused. Reconnect the Google account.",
  SOURCE_GOOGLE_CONNECTION_UNAVAILABLE:
    "The Google connection is unavailable. Check its status and reconnect if needed.",
  SOURCE_GOOGLE_UNSUPPORTED: "This Google Drive item is not supported for acquisition.",
  SOURCE_GOOGLE_LIMIT_EXCEEDED:
    "This item exceeds the supported acquisition limits and was not imported.",
  SOURCE_GOOGLE_INCONSISTENT: "This file changed while it was being acquired. Synchronize again.",
  SOURCE_GOOGLE_INCOMPLETE:
    "Some files could not be acquired. Review the file errors and synchronize again.",
};

function sourceStatusMessage(code: string) {
  const known = statusMessages[code];
  if (known) return known;
  if (isSafeCode(code)) return `Source processing failed. Error reference: ${code}.`;
  return "Source processing failed. Try the operation again.";
}

function sourceMutationError(error: unknown, mutation: SourceMutation) {
  if (error instanceof ApiError) {
    const code = problemCode(error);
    if (mutation === "google-drive-schedule") {
      if (isGoogleDriveRevisionConflict(error) || error.status === 428)
        return "The automatic interval changed. Reload the saved interval before trying again.";
      if (error.status === 400 || error.status === 422)
        return "Enter a whole number of minutes from 1 to 2147483647.";
    }
    if (code && statusMessages[code]) return statusMessages[code];
    if (mutation === "google-drive") {
      if (error.status === 412 || error.status === 428)
        return statusMessages.SOURCE_GOOGLE_REVISION_CONFLICT;
      if (error.status === 409)
        return "This source or credential changed, or the credential is still used by a Source. Refresh its status before trying again.";
      if (error.status === 400 || error.status === 422)
        return "Check the credential or Source name, OAuth client JSON, and 1–20 unique, non-overlapping HTTPS Google file or folder links.";
    }
    if (error.status === 403) return "Only an active Tenant owner can manage sources.";
    if (mutation === "google-drive" && error.status === 404)
      return "This Source or credential is no longer available. Refresh and select another credential.";
    if (error.status === 404) return unavailableMessage(mutation);
    if (error.status === 409) return conflictMessage(mutation);
    if (error.status === 400 || error.status === 413)
      return "Check the source name or uploaded file and try again.";
    if (code && isSafeCode(code))
      return `The source operation could not be completed. Error reference: ${code}.`;
  }

  return mutation === "google-drive-schedule"
    ? "The automatic interval could not be updated. Try again."
    : "The source operation could not be completed. Try again.";
}

function unavailableMessage(mutation: SourceMutation) {
  if (mutation === "remove-item") return "This file is no longer available in the source.";
  if (mutation === "delete-source") return "This source is no longer available.";
  return "The source or file is no longer available.";
}

function conflictMessage(mutation: SourceMutation) {
  if (mutation === "remove-item")
    return "This file is already changing. Refresh the source and try again.";
  if (mutation === "delete-source")
    return "This source is already changing. Refresh the source and try again.";
  return "The source cannot accept that operation right now.";
}

function problemCode(error: ApiError) {
  const cause = error.cause;
  if (!cause || typeof cause !== "object" || !("code" in cause)) return undefined;
  const code = cause.code;
  return typeof code === "string" ? code : undefined;
}

function isGoogleDriveRevisionConflict(error: unknown) {
  return (
    error instanceof ApiError &&
    (error.status === 412 || problemCode(error) === "SOURCE_GOOGLE_REVISION_CONFLICT")
  );
}

function isSafeCode(code: string) {
  return /^[A-Z][A-Z0-9_]{2,80}$/.test(code);
}

export {
  isGoogleDriveRevisionConflict,
  sourceMutationError,
  sourceStatusMessage,
  type SourceMutation,
};
