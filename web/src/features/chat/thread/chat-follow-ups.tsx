import { SuggestionPrimitive, ThreadPrimitive, useAuiState } from "@assistant-ui/react";
import { Button } from "@/components/ui/button";
import { Skeleton } from "@/components/ui/skeleton";
import { useAppTranslation } from "@/i18n/use-app-translation";

/** The chips stack at full width on a narrow thread and wrap in a row on a wide one (Figma 4:25369, 4:25417). */
const LAYOUT = "mt-4 flex flex-col items-stretch gap-2 @xl/thread:flex-row @xl/thread:flex-wrap";

/**
 * MEM-266, the assistant-ui Follow-up suggestions element: the questions the API wrote for the latest answer, under
 * its action bar. Choosing one asks it at once, unlike a conversation starter, which fills the composer. While they are
 * written, three placeholders hold their place (Figma 4:25393); none came, or the request failed, shows nothing.
 * `pendingFor` is the answer whose questions are being written.
 */
export function ChatFollowUps({ pendingFor }: { pendingFor?: string }) {
  const ui = useAppTranslation();
  const isLast = useAuiState((state) => state.message.isLast);
  const messageId = useAuiState((state) => state.message.id);
  const running = useAuiState((state) => state.thread.isRunning);
  const count = useAuiState((state) => state.thread.suggestions.length);
  if (!isLast || running) return null;
  if (count === 0)
    return pendingFor === messageId ? (
      <div role="status" aria-label={ui("Đang gợi ý câu hỏi tiếp theo")} className={LAYOUT}>
        <Skeleton className="h-9.5 @xl/thread:w-80" />
        <Skeleton className="h-9.5 @xl/thread:w-52" />
        <Skeleton className="h-9.5 @xl/thread:w-84" />
      </div>
    ) : null;
  return (
    <div className={LAYOUT}>
      <ThreadPrimitive.Suggestions>
        {() => (
          // Inside a message the composer in scope is that message's edit composer, which an answer does not have,
          // so the person's draft in the thread composer is left as it is.
          <SuggestionPrimitive.Trigger send clearComposer={false} asChild>
            <Button
              prominence="secondary"
              className="h-auto max-w-full justify-start py-2 text-left whitespace-normal"
            >
              <SuggestionPrimitive.Title />
            </Button>
          </SuggestionPrimitive.Trigger>
        )}
      </ThreadPrimitive.Suggestions>
    </div>
  );
}
