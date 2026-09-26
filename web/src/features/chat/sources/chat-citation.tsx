import { useAppTranslation } from "@/i18n/use-app-translation";
import { use, useState, type ComponentProps, type ReactNode } from "react";
import { ArrowUpRight } from "lucide-react";
import { InlineCitation } from "@/components/assistant-ui/elements/inline-citation";
import { SourceIcon } from "@/components/assistant-ui/elements/source-icon";
import { DocumentSourceIcon } from "@/features/documents/document-source-icon";
import { DocumentMeta } from "@/features/documents/provider-link";
import { ChatPanelContext as PanelContext } from "@/features/chat/thread/chat-panel-context";
import type { ChatSource } from "./chat-evidence";
import { EvidenceContext } from "./chat-evidence-context";
import { SourceExcerpt } from "./chat-source-excerpt";
import { sourceLocationLabels, webDisplayUrl } from "./chat-source-meta";

function Citation({ source }: { source: ChatSource }) {
  const ui = useAppTranslation();

  const [open, setOpen] = useState(false);
  const { messageId } = use(EvidenceContext);
  const panel = use(PanelContext);
  const active = panel.messageId === messageId && panel.citationId === source.citationId;
  const webHost = source.web ? new URL(source.web.url).hostname.replace(/^www\./, "") : undefined;
  const icon = webHost ? (
    <SourceIcon domain={webHost} fallback="none" />
  ) : (
    <DocumentSourceIcon
      size="xs"
      mediaType={source.mediaType}
      filename={source.title}
      sourceTypes={source.sourceTypes}
    />
  );
  return (
    <InlineCitation
      open={open}
      onOpenChange={setOpen}
      aria-label={ui("Mở nguồn {{v1}}: {{v2}}", { v1: source.citationId, v2: source.title })}
      aria-current={active || undefined}
      className={active ? "border-border-strong bg-surface-sunken text-content-primary" : undefined}
      onClick={(event) => {
        setOpen(false);
        panel.open(messageId, event.currentTarget, source.citationId);
      }}
      icon={icon}
      preview={
        <>
          <div className="flex min-w-0 items-start gap-3">
            {source.web ? (
              <span className="flex size-8 shrink-0 items-center justify-center rounded-lg border border-border-subtle bg-surface-subtle">
                <SourceIcon domain={webHost!} fallback="globe" />
              </span>
            ) : (
              <DocumentSourceIcon
                mediaType={source.mediaType}
                filename={source.title}
                sourceTypes={source.sourceTypes}
              />
            )}
            <div className="min-w-0 flex-1">
              <p
                className="line-clamp-2 break-words text-sm font-medium leading-5"
                title={source.title}
              >
                {source.title}
              </p>
              <p className="mt-0.5 break-words text-xs leading-5 text-content-muted">
                {source.web ? (
                  ui("Web · Nguồn {{number}}", { number: source.citationId })
                ) : (
                  <DocumentMeta
                    lead={[ui("Nguồn {{number}}", { number: source.citationId })]}
                    trail={sourceLocationLabels(source, ui)}
                    mediaType={source.mediaType}
                    sourceTypes={source.sourceTypes}
                    providerUrl={source.providerUrl}
                    title={source.title}
                  />
                )}
              </p>
              {source.web ? (
                <a
                  href={source.web.url}
                  target="_blank"
                  rel="noopener noreferrer"
                  title={source.web.url}
                  data-slot="source-url"
                  className="mt-0.5 flex min-w-0 items-center gap-0.5 rounded-sm text-xs leading-5 text-content-secondary underline decoration-border-default underline-offset-4 hover:text-content-primary hover:decoration-current focus-visible:outline-hidden focus-visible:ring-3 focus-visible:ring-focus-ring/30"
                >
                  <span className="truncate">{webDisplayUrl(source.web.url)}</span>
                  <ArrowUpRight className="size-3 shrink-0" aria-hidden="true" />
                </a>
              ) : null}
            </div>
          </div>
          <SourceExcerpt
            source={source}
            className="mt-3 line-clamp-4 border-l-2 border-evidence-highlight-border pl-3"
          />
        </>
      }
    >
      <span className="font-semibold tabular-nums">{source.citationId}</span>
      {/* Web chips name the site, like AI Elements' hostname badge; the hover card keeps the page title. */}
      <span className="text-current/70"> · {webHost ?? source.title}</span>
    </InlineCitation>
  );
}

function textOf(node: ReactNode): string {
  if (typeof node === "string" || typeof node === "number") return String(node);
  if (Array.isArray(node)) return node.map(textOf).join("");
  return "file";
}

export function ChatMarkdownLink({ href, children }: ComponentProps<"a">) {
  const { sources } = use(EvidenceContext);
  const panel = use(PanelContext);
  const citation = /^#citation-(\d{1,2})$/.exec(href ?? "");
  if (citation) {
    const source = sources.find((item) => item.citationId === Number(citation[1]));
    return source ? <Citation source={source} /> : <span>{children}</span>;
  }
  // Files that run_python generated are ours and are served from this origin; everything else
  // relative is model-written text, not a link.
  const generatedId = /^\/api\/chat\/file-artifacts\/([0-9a-fA-F-]{36})\/content$/.exec(
    href ?? "",
  )?.[1];
  if (generatedId)
    return (
      // Onyx MemoizedTextComponents: a link to a chat file opens the preview instead of downloading.
      <button
        type="button"
        data-slot="generated-file"
        aria-haspopup="dialog"
        onClick={(event) =>
          panel.previewFile(
            {
              source: "generated",
              id: generatedId,
              filename: typeof children === "string" ? children : textOf(children),
            },
            event.currentTarget,
          )
        }
        className="inline cursor-pointer text-primary underline underline-offset-2"
      >
        {children}
      </button>
    );
  if (!href || !/^https?:\/\//i.test(href)) return <span>{children}</span>;
  const domain = new URL(href).hostname.replace(/^www\./, "");
  // Only hostnames of this answer's Web sources may reach the favicon service; other links,
  // possibly internal hosts written by the model, show the letter fallback.
  const webSource = sources.some(
    (source) => source.web && new URL(source.web.url).hostname.replace(/^www\./, "") === domain,
  );
  const label = typeof children === "string" && children !== href ? children : domain;
  // Inline URL source chip adapted from the assistant-ui Sources element (outline, sm).
  return (
    <a
      data-slot="source"
      href={href}
      target="_blank"
      rel="noopener noreferrer"
      title={href}
      className="mx-0.5 inline-flex max-w-64 items-center gap-1.5 rounded-md border border-border-default px-1.5 py-0.5 align-middle text-xs text-content-secondary no-underline transition-colors hover:bg-surface-sunken hover:text-content-primary"
    >
      <SourceIcon domain={domain} favicon={webSource} />
      <span data-slot="source-title" className="truncate">
        {label}
      </span>
    </a>
  );
}
