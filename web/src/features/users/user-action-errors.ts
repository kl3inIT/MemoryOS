import { ApiError, problemCode } from "@/lib/api";

export function invitationError(error: unknown) {
  if (error instanceof ApiError) {
    const code = problemCode(error);
    if (code === "IDENTITY_PROVISIONING_ACCOUNT_CONFLICT") {
      return "This email belongs to an identity account that cannot be reused. Contact an administrator.";
    }
    if (code === "INVITATION_CONFLICT") {
      return "An open invitation already exists for this email.";
    }
    if (error.status === 401) return "Your session expired. Sign in and try again.";
    if (error.status === 409) {
      return "The account or invitation state has changed. Refresh the page and try again.";
    }
    if (error.status === 403) return "Only an active Tenant owner can manage invitations.";
    if (error.status === 410) {
      return "This invitation is no longer available. Refresh the page and try again.";
    }
    if (error.status === 400) return "Enter a valid email address.";
    if (error.status === 503) return "The activation email could not be sent. Try again.";
  }
  return "The invitation could not be updated. Try again.";
}

export function membershipActionError(error: unknown) {
  if (error instanceof ApiError) {
    if (error.status === 401) return "Your session expired. Sign in and try again.";
    if (error.status === 403) return "This access change is not allowed.";
    if (error.status === 404) return "This user is no longer part of the Tenant.";
  }
  return "The user’s access could not be updated. Try again.";
}
