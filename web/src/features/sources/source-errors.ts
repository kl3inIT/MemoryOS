import { ApiError, problemCode } from "@/lib/api";

type SourceMutation =
  | "create"
  | "upload"
  | "reindex"
  | "remove-item"
  | "delete-source"
  | "associations";
type SourceActionFailure = "cleanup-failed" | "cleanup-timeout" | "invalid-cleanup-response";

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
};

const actionMessages: Record<SourceActionFailure, string> = {
  "cleanup-failed": "Source cleanup failed. Try deleting the source again.",
  "cleanup-timeout": "Source cleanup is taking too long. Refresh before trying again.",
  "invalid-cleanup-response": "Source cleanup could not be tracked. Refresh before trying again.",
};

class SourceActionError extends Error {
  readonly failure: SourceActionFailure;

  constructor(failure: SourceActionFailure) {
    super(failure);
    this.name = "SourceActionError";
    this.failure = failure;
  }
}

function sourceStatusMessage(code: string) {
  const known = statusMessages[code];
  if (known) return known;
  if (isSafeCode(code)) return `Source processing failed. Error reference: ${code}.`;
  return "Source processing failed. Try the operation again.";
}

function sourceMutationError(error: unknown, mutation: SourceMutation) {
  if (error instanceof SourceActionError) return actionMessages[error.failure];

  if (error instanceof ApiError) {
    const code = problemCode(error);
    if (code && statusMessages[code]) return statusMessages[code];
    if (error.status === 403) return "You do not have permission to manage this Source.";
    if (error.status === 404) return unavailableMessage(mutation);
    if (error.status === 409) return conflictMessage(mutation);
    if (error.status === 400 && mutation === "associations")
      return "Every Source must remain associated with at least one group.";
    if (error.status === 400 || error.status === 413)
      return "Check the source name or uploaded file and try again.";
    if (code && isSafeCode(code))
      return `The source operation could not be completed. Error reference: ${code}.`;
  }

  return "The source operation could not be completed. Try again.";
}

function unavailableMessage(mutation: SourceMutation) {
  if (mutation === "associations") return "This Source is no longer available.";
  if (mutation === "remove-item") return "This file is no longer available in the source.";
  if (mutation === "delete-source") return "This source is no longer available.";
  return "The source or file is no longer available.";
}

function conflictMessage(mutation: SourceMutation) {
  if (mutation === "associations")
    return "Source associations changed while you were editing. Refresh and try again.";
  if (mutation === "remove-item")
    return "This file is already changing. Refresh the source and try again.";
  if (mutation === "delete-source")
    return "This source is already changing. Refresh the source and try again.";
  return "The source cannot accept that operation right now.";
}

function isSafeCode(code: string) {
  return /^[A-Z][A-Z0-9_]{2,80}$/.test(code);
}

export { SourceActionError, sourceMutationError, sourceStatusMessage, type SourceMutation };
