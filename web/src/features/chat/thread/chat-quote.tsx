import { useAppTranslation } from "@/i18n/use-app-translation";
import {
  ComposerPrimitive,
  MessagePrimitive,
  SelectionToolbarPrimitive,
  type TextMessagePartProps,
} from "@assistant-ui/react";
import { TextQuote, X } from "lucide-react";
import { Button } from "@/components/ui/button";
import { IconButton } from "@/components/ui/icon-button";

/** Floating Quote action for a passage selected inside one answer. */
export function ChatSelectionToolbar() {
  const ui = useAppTranslation();
  return (
    <SelectionToolbarPrimitive.Root className="rounded-xl border border-border-subtle bg-surface-overlay p-1 shadow-md">
      <SelectionToolbarPrimitive.Quote asChild>
        <Button size="sm" prominence="internal">
          <TextQuote data-icon="inline-start" aria-hidden="true" />
          {ui("Trích dẫn")}
        </Button>
      </SelectionToolbarPrimitive.Quote>
    </SelectionToolbarPrimitive.Root>
  );
}

/** Pending quote above the composer input; it is sent as a blockquote before the question. */
export function ChatComposerQuote() {
  const ui = useAppTranslation();
  return (
    <ComposerPrimitive.Quote
      aria-label={ui("Đoạn trích dẫn")}
      className="flex items-start gap-2 rounded-xl bg-surface-sunken px-3 py-2 text-sm text-content-secondary"
    >
      <TextQuote aria-hidden="true" className="mt-0.5 size-4 shrink-0" />
      <ComposerPrimitive.QuoteText className="line-clamp-2 min-w-0 flex-1 wrap-anywhere" />
      <ComposerPrimitive.QuoteDismiss asChild>
        <IconButton size="sm" prominence="internal" aria-label={ui("Bỏ trích dẫn")}>
          <X />
        </IconButton>
      </ComposerPrimitive.QuoteDismiss>
    </ComposerPrimitive.Quote>
  );
}

function QuoteBlock({ text }: { text: string }) {
  return (
    <span
      data-slot="quote-block"
      className="mb-2 flex items-start gap-2 border-l-2 border-border-strong pl-2 text-sm text-content-secondary"
    >
      <span className="line-clamp-3">{text}</span>
    </span>
  );
}

/** A live question shows its quote from message metadata before the server transcript reloads. */
export function ChatUserMessageQuote() {
  return (
    <MessagePrimitive.Quote>{({ text }) => <QuoteBlock text={text} />}</MessagePrimitive.Quote>
  );
}

/** Saved questions carry the quote as a leading Markdown blockquote in their text. */
export function ChatUserText({ text }: TextMessagePartProps) {
  const quoted = /^((?:> .*(?:\n|$))+)\n/.exec(text);
  const block = quoted?.[1];
  if (!quoted || block === undefined) return text;
  const quote = block
    .replace(/\n$/, "")
    .split("\n")
    .map((line) => line.slice(2))
    .join("\n");
  return (
    <>
      <QuoteBlock text={quote} />
      <span>{text.slice(quoted[0].length)}</span>
    </>
  );
}
