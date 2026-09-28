import { appText, type AppCopy } from "@/i18n/app-text";
import type { useActionNotifications } from "@/components/ui/action-notifications";
import type { SourceOperation } from "@/lib/hey-api/types.gen";
import { sourceStatusMessage } from "./source-errors";

export type SourceOperationNotice = Parameters<ReturnType<typeof useActionNotifications>>[0];

/**
 * The notice for a settled Source operation: one title per outcome, the thing it acted on as the
 * description, and the failure's safe message. Only what differs between commands is passed in.
 */
export function sourceOperationNotice(
  operation: SourceOperation,
  {
    subject,
    titles,
    succeeded = subject,
    superseded = appText("{{v1}}: this request was replaced by newer work.", { v1: subject }),
    failureCode,
    failureSubject = true,
  }: {
    /** The file or Source the command acted on. */
    subject: AppCopy;
    titles: { succeeded: AppCopy; superseded: AppCopy; cancelled: AppCopy; failed: AppCopy };
    succeeded?: AppCopy;
    superseded?: AppCopy;
    /** Reported when a failed operation carries no error code of its own. */
    failureCode: string;
    /** Whether the failure message is prefixed with the subject. */
    failureSubject?: boolean;
  },
): SourceOperationNotice {
  if (operation.status === "SUCCEEDED")
    return { tone: "success", title: titles.succeeded, description: succeeded };
  if (operation.status === "SUPERSEDED")
    return { tone: "info", title: titles.superseded, description: superseded };
  if (operation.status === "CANCELLED")
    return { tone: "info", title: titles.cancelled, description: subject };
  const message = appText(sourceStatusMessage(operation.errorCode ?? failureCode));
  return {
    tone: "error",
    title: titles.failed,
    description: failureSubject ? appText("{{v1}}: {{v2}}", { v1: subject, v2: message }) : message,
  };
}
