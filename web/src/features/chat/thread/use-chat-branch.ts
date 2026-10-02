import { useMutation } from "@tanstack/react-query";
import { useNavigate } from "@tanstack/react-router";
import { useAppTranslation } from "@/i18n/use-app-translation";
import { branchChatSessionMutation } from "@/lib/hey-api/@tanstack/react-query.gen";
import { actionErrorText } from "@/lib/action-errors";
import { useRefreshChatSessions } from "@/features/chat/runtime/chat-threads-context";

/**
 * "Branch into a new chat" on one message, after Gemini's answer menu: the conversation so far is copied into a
 * new one and that one opens, so trying another direction leaves this conversation exactly as it is. A question
 * offers it as its own icon; an answer offers it in its "…" menu.
 */
export function useChatBranch({
  sessionId,
  messageId,
  originTitle,
}: {
  sessionId: string;
  messageId: string;
  /** The branch is named here, so its title reads in the language the person is using. */
  originTitle?: string;
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
  return {
    start: () =>
      branch.mutate({
        path: { sessionId, messageId },
        body: originTitle
          ? { title: ui("Nhánh của {{title}}", { title: originTitle }).slice(0, 200) }
          : {},
        signal: AbortSignal.timeout(120_000),
      }),
    pending: branch.isPending,
    error: branch.isError ? ui(actionErrorText(branch.error)) : undefined,
  };
}
