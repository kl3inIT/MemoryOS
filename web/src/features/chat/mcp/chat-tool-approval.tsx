import { useState } from "react";
import { ShieldQuestion } from "lucide-react";
import { ApprovalCard } from "@/components/assistant-ui/elements/approval-card";
import { FieldError } from "@/components/ui/field";
import type { ToolPart } from "@/features/chat/activity/chat-activity-labels";
import { useAppTranslation } from "@/i18n/use-app-translation";
import { presentProblem } from "@/lib/problem-presentation";
import { useProblemMessage } from "@/lib/use-problem-message";

/** What the server streamed with the gate (MEM-253): the app, the tool and the call's JSON arguments. */
type Descriptor = { serverName?: string; toolName?: string; arguments?: string };

/**
 * The arguments as the person reads them: each field of a JSON object on its own row, text as written (an email
 * body keeps its lines), anything else as indented JSON. A value that does not parse is one row as sent.
 */
function details(json: string | undefined): { label: string; value: string }[] {
  if (!json) return [];
  try {
    const value: unknown = JSON.parse(json);
    if (value && typeof value === "object" && !Array.isArray(value))
      return Object.entries(value).map(([label, field]) => ({
        label,
        value: typeof field === "string" ? field : JSON.stringify(field, null, 2),
      }));
    return [{ label: "", value: JSON.stringify(value, null, 2) }];
  } catch {
    return [{ label: "", value: json }];
  }
}

/**
 * A call that waits for the person, rendered with assistant-ui's approval card on the tool part's own gate: Allow
 * or Decline while it waits, then a receipt. The answer goes through `respondToApproval`, which the runtime sends
 * to the server that holds the paused call.
 */
export function ChatToolApproval({
  part,
  tool,
  server,
}: {
  part: ToolPart;
  tool: string;
  server: string;
}) {
  const ui = useAppTranslation();
  const problem = useProblemMessage();
  const [failure, setFailure] = useState<unknown>(null);
  const approval = part.approval;
  if (!approval) return null;
  const descriptor = (approval as { descriptor?: Descriptor }).descriptor ?? {};
  const answer = async (approved: boolean) => {
    setFailure(null);
    try {
      await part.respondToApproval({ approved });
    } catch (cause) {
      setFailure(cause);
    }
  };
  const state =
    approval.approved === undefined
      ? "request"
      : approval.approved === false
        ? "denied"
        : part.status.type === "running" || part.status.type === "requires-action"
          ? "running"
          : "done";
  return (
    <div className="flex flex-col gap-1">
      <ApprovalCard
        state={state}
        icon={<ShieldQuestion className="size-4" />}
        title={ui("Cho phép {{tool}} trên {{server}}?", {
          tool: descriptor.toolName ?? tool,
          server: descriptor.serverName ?? server,
        })}
        subtitle={ui("Công cụ này có thể thay đổi dữ liệu")}
        details={details(descriptor.arguments)}
        allowOnceLabel={ui("Cho phép")}
        denyLabel={ui("Từ chối")}
        statusLabel={
          state === "denied"
            ? ui("Bạn đã từ chối.")
            : state === "running"
              ? ui("Đang chạy…")
              : state === "done"
                ? ui("Bạn đã cho phép.")
                : undefined
        }
        onAllowOnce={() => void answer(true)}
        onDeny={() => void answer(false)}
      />
      {failure ? (
        <FieldError>{problem(presentProblem(failure, "mutation").message)}</FieldError>
      ) : null}
    </div>
  );
}
