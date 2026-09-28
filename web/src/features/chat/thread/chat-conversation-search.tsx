import { useEffect, useMemo, useState } from "react";
import { useAuiState } from "@assistant-ui/react";
import { Search } from "lucide-react";
import { ConversationSearch } from "@/components/assistant-ui/elements/conversation-search";
import { Popover, PopoverContent, PopoverTrigger } from "@/components/ui/popover";
import { IconButton } from "@/components/ui/icon-button";
import { useAppTranslation } from "@/i18n/use-app-translation";

import { findConversationHits } from "./chat-conversation-matches";

export function ChatConversationSearch() {
  const ui = useAppTranslation();
  const [open, setOpen] = useState(false);
  const [query, setQuery] = useState("");
  const [index, setIndex] = useState(0);
  const messages = useAuiState((state) => state.thread.messages);
  const hits = useMemo(
    () => (open ? findConversationHits(messages, query) : []),
    [messages, query, open],
  );
  const active = hits[Math.min(index, Math.max(0, hits.length - 1))];
  const messageId = active?.messageId;
  const hitId = active?.id;
  useEffect(() => {
    if (!messageId) return;
    const element = document.querySelector<HTMLElement>(
      `[data-message-id="${CSS.escape(messageId)}"]`,
    );
    element?.scrollIntoView({ block: "center", behavior: "instant" });
    element?.setAttribute("data-chat-search-match", "true");
    return () => element?.removeAttribute("data-chat-search-match");
  }, [hitId, messageId]);
  if (messages.length === 0) return null;
  return (
    <Popover open={open} onOpenChange={setOpen}>
      <PopoverTrigger asChild>
        <IconButton size="sm" prominence="internal" aria-label={ui("Tìm trong hội thoại")}>
          <Search />
        </IconButton>
      </PopoverTrigger>
      <PopoverContent
        align="end"
        collisionPadding={16}
        className="w-96 max-w-[calc(100vw-2rem)] p-3"
      >
        <ConversationSearch
          query={query}
          hits={hits}
          activeIndex={index}
          onQueryChange={(value) => {
            setQuery(value);
            setIndex(0);
          }}
          onStep={(delta) => {
            if (hits.length)
              setIndex(
                (current) =>
                  (Math.min(current, hits.length - 1) + delta + hits.length) % hits.length,
              );
          }}
        />
        {hits.length === 500 && (
          <p role="status" className="mt-1 text-xs text-content-muted">
            {ui("Hiển thị tối đa 500 kết quả. Hãy nhập cụ thể hơn.")}
          </p>
        )}
      </PopoverContent>
    </Popover>
  );
}
