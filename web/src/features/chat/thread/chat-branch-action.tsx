import { useMutation } from "@tanstack/react-query";
import { Link, useNavigate } from "@tanstack/react-router";
import { GitBranch } from "lucide-react";
import { IconButton } from "@/components/ui/icon-button";
import { useAppTranslation } from "@/i18n/use-app-translation";
import { branchChatSessionMutation } from "@/lib/hey-api/@tanstack/react-query.gen";
import type { ChatSession } from "@/lib/hey-api/types.gen";
import { actionErrorText } from "@/lib/action-errors";
import { useRefreshChatSessions } from "@/features/chat/runtime/chat-threads-context";

/**
 * "Branch into a new chat" on one message, after Gemini's answer menu: the conversation so far is copied into a
 * new one and that one opens, so trying another direction leaves this conversation exactly as it is.
 */
export function ChatBranchAction({
  sessionId,
  messageId,
  originTitle,
  disabled,
}: {
  sessionId: string;
  messageId: string;
  /** The branch is named here, so its title reads in the language the person is using. */
  originTitle?: string;
  disabled?: boolean;
}) {
  const ui = useAppTranslation();
  const refreshSessions = useRefreshChatSessions();
  const navigate = useNavigate();
  const branch = useMutation({
    ...branchChatSessionMutation(),
    onSuccess: async (created) => {
      await refreshSessions();
      await navigate({ to: "/chat/$sessionId", params: { sessionId: created.id } });
    },
  });
  return (
    <>
      <IconButton
        size="sm"
        prominence="internal"
        aria-label={ui("Tách sang hội thoại mới")}
        title={ui("Tách sang hội thoại mới")}
        pending={branch.isPending}
        disabled={disabled || branch.isPending}
        onClick={() =>
          branch.mutate({
            path: { sessionId, messageId },
            body: originTitle
              ? { title: ui("Nhánh của {{title}}", { title: originTitle }).slice(0, 200) }
              : {},
            signal: AbortSignal.timeout(120_000),
          })
        }
      >
        <GitBranch />
      </IconButton>
      {branch.isError && (
        <p role="alert" className="text-xs text-status-danger-content">
          {ui(actionErrorText(branch.error))}
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
