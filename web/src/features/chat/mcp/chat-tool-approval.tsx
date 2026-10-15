import { type ReactNode, useState } from "react";
import { ShieldQuestion } from "lucide-react";
import { ApprovalCard } from "@/components/assistant-ui/elements/approval-card";
import { FieldError } from "@/components/ui/field";
import type { ToolPart } from "@/features/chat/activity/chat-activity-labels";
import { useAppTranslation } from "@/i18n/use-app-translation";
import { presentProblem } from "@/lib/problem-presentation";
import { useProblemMessage } from "@/lib/use-problem-message";

/** What the server streamed with the gate (MEM-253): the app, the tool, its display name and the call's JSON arguments. */
type Descriptor = { serverName?: string; toolName?: string; toolTitle?: string; arguments?: string };

/** The fields assistant-ui reads from the gate's descriptor (MEM-267): the answers offered and how it ended. */
type Gate = {
  options?: readonly { id: string; kind: string }[];
  optionId?: string;
  resolution?: "cancelled" | "expired";
};

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
 * or Decline while it waits, plus Always allow when the gate offers it, then a receipt. The answer goes through
 * `respondToApproval`, which the runtime sends to the server that holds the paused call. A call nobody answered in
 * time, or a reloaded answer, shows only its receipt.
 */
export function ChatToolApproval({
  part,
  tool,
  server,
  mark,
}: {
  part: ToolPart;
  tool: string;
  server: string;
  /** The app's logo, which takes the place of the generic icon. */
  mark?: ReactNode;
}) {
  const ui = useAppTranslation();
  const problem = useProblemMessage();
  const [failure, setFailure] = useState<unknown>(null);
  const approval = part.approval;
  if (!approval) return null;
  const descriptor = (approval as { descriptor?: Descriptor & Gate }).descriptor ?? {};
  const gate = { ...descriptor, ...(approval as Gate) };
  const alwaysAllowable = gate.options?.some((option) => option.kind === "allow-always") ?? false;
  const answer = async (approved: boolean, always = false) => {
    setFailure(null);
    try {
      await part.respondToApproval(
        always ? { approved: true, optionId: "allow-always" } : { approved },
      );
    } catch (cause) {
      setFailure(cause);
    }
  };
  const expired = gate.resolution === "expired";
  const state =
    expired || approval.approved === false
      ? "denied"
      : approval.approved === undefined
        ? "request"
        : part.status.type === "running" || part.status.type === "requires-action"
          ? "running"
          : "done";
  return (
    <div className="flex flex-col gap-1">
      <ApprovalCard
        state={state}
        icon={<ShieldQuestion className="size-4" />}
        mark={mark}
        title={ui("Cho phép {{tool}} trên {{server}}?", {
          tool: descriptor.toolTitle ?? descriptor.toolName ?? tool,
          server: descriptor.serverName ?? server,
        })}
        details={details(descriptor.arguments)}
        allowOnceLabel={ui("Cho phép")}
        alwaysAllowLabel={ui("Luôn cho phép")}
        denyLabel={ui("Từ chối")}
        statusLabel={
          expired
            ? ui("Hết thời gian chờ, công cụ không chạy.")
            : state === "denied"
              ? ui("Bạn đã từ chối.")
              : state === "running"
                ? ui("Đang chạy…")
                : state === "done"
                  ? gate.optionId === "allow-always"
                    ? ui("Đã bật luôn cho phép công cụ này.")
                    : ui("Bạn đã cho phép.")
                  : undefined
        }
        onAllowOnce={() => void answer(true)}
        onAlwaysAllow={alwaysAllowable ? () => void answer(true, true) : undefined}
        onDeny={() => void answer(false)}
      />
      {failure ? (
        <FieldError>{problem(presentProblem(failure, "mutation").message)}</FieldError>
      ) : null}
    </div>
  );
}
