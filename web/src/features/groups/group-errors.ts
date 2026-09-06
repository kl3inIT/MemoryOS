import { ApiError, problemCode } from "@/lib/api";

type GroupMutation =
  | "create"
  | "rename"
  | "delete"
  | "members"
  | "manager"
  | "capabilities"
  | "sources";

export function groupMutationError(error: unknown, mutation: GroupMutation) {
  if (error instanceof ApiError) {
    const code = problemCode(error);
    if (error.status === 401) return "Your session expired. Sign in and try again.";
    if (error.status === 403) return "You no longer have permission to make this change.";
    if (error.status === 404) {
      if (mutation === "members" || mutation === "manager") {
        return "The group or member is no longer available. Refresh and try again.";
      }
      return "This group is no longer available.";
    }
    if (error.status === 409) {
      if (mutation === "create" || mutation === "rename") {
        return "Another group already uses that name.";
      }
      return "The group changed while you were editing it. Refresh and try again.";
    }
    if (error.status === 400) {
      if (mutation === "create" || mutation === "rename")
        return "Enter a valid, unique group name.";
      if (mutation === "sources")
        return "Every source must stay associated with at least one group.";
      return "The requested group change is not valid.";
    }
    if (code && /^[A-Z][A-Z0-9_]{2,80}$/.test(code)) {
      return `The group change could not be completed. Error reference: ${code}.`;
    }
  }
  return "The group change could not be completed. Try again.";
}
