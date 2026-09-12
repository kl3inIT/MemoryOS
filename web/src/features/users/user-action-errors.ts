import { presentProblem, type ErrorMessage } from "@/lib/problem-presentation";

const messages = {
  IDENTITY_PROVISIONING_ACCOUNT_CONFLICT: { key: "identityConflict" },
  INVITATION_CONFLICT: { key: "invitationConflict" },
  IAM_CONFIGURED_OWNER_PROTECTED: { key: "ownerProtected" },
  IAM_LAST_ADMIN_PROTECTED: { key: "lastAdmin" },
} satisfies Record<string, ErrorMessage>;

export function invitationError(error: unknown) {
  const problem = presentProblem(error, "mutation", messages);
  return problem.fields.email ?? problem.message;
}

export function membershipActionError(error: unknown) {
  return presentProblem(error, "mutation", messages).message;
}
