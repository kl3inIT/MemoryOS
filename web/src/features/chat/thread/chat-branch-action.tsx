import { Link } from "@tanstack/react-router";
import { TooltipIconButton } from "@/components/composites/tooltip-icon-button";
import { GitBranch } from "lucide-react";
import { useAppTranslation } from "@/i18n/use-app-translation";
import type { ChatSession } from "@/lib/hey-api/types.gen";
import { useChatBranch } from "./use-chat-branch";

/** "Branch into a new chat" as a question's own icon; an answer offers it in its "…" menu. */
export function ChatBranchAction({
  sessionId,
  messageId,
  originTitle,
  disabled,
}: {
  sessionId: string;
  messageId: string;
  originTitle?: string;
  disabled?: boolean;
}) {
  const ui = useAppTranslation();
  const branch = useChatBranch({ sessionId, messageId, originTitle });
  return (
    <>
      <TooltipIconButton
        size="sm"
        prominence="internal"
        aria-label={ui("Tách sang hội thoại mới")}
        pending={branch.pending}
        disabled={disabled || branch.pending}
        onClick={branch.start}
      >
        <GitBranch />
      </TooltipIconButton>
      {branch.error && (
        <p role="alert" className="text-xs text-status-danger-content">
          {branch.error}
        </p>
      )}
    </>
  );
}

/** Where a branch came from, so its own header leads back to the conversation it was taken out of. */
export function ChatBranchOrigin({
  session,
}: {
  session: Pick<ChatSession, "branchedFromSessionId">;
}) {
  const ui = useAppTranslation();
  if (!session.branchedFromSessionId) return null;
  return (
    <Link
      to="/chat/$sessionId"
      params={{ sessionId: session.branchedFromSessionId }}
      className="flex items-center gap-1 rounded-full border border-border-subtle px-2 py-0.5 font-secondary-body text-content-muted hover:text-content-primary"
    >
      <GitBranch className="size-3.5" aria-hidden="true" />
      {ui("Tách từ hội thoại gốc")}
    </Link>
  );
}
