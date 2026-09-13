import type { SearchHit } from "@/components/assistant-ui/elements/conversation-search";

type SearchMessage = { id: string; parts: readonly { type: string; text?: string }[] };
export function findConversationHits(
  messages: readonly SearchMessage[],
  query: string,
): (SearchHit & { messageId: string })[] {
  const needle = query.trim();
  if (!needle) return [];
  // Literal query, Unicode matching, and offsets in the original text.
  const escaped = needle.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
  const pattern = new RegExp(escaped, "giu");
  const hits: (SearchHit & { messageId: string })[] = [];
  for (const [index, message] of messages.entries()) {
    const text = message.parts
      .filter((part) => part.type === "text")
      .map((part) => part.text ?? "")
      .join("\n");
    for (const match of text.matchAll(pattern)) {
      hits.push({
        id: `${message.id}:${match.index}`,
        messageId: message.id,
        before: text.slice(Math.max(0, match.index - 40), match.index),
        match: match[0],
        after: text.slice(match.index + match[0].length, match.index + match[0].length + 60),
        position: (index / Math.max(1, messages.length - 1)) * 95,
      });
      if (hits.length === 500) return hits;
    }
  }
  return hits;
}
