import { lazy, Suspense, use, useId, useRef, useState, type ReactNode } from "react";
import { useAuiState } from "@assistant-ui/react";
import { Sources } from "@/components/assistant-ui/elements/sources";
import { ChatSourcePanel } from "./chat-source-panel";
import type { ChatSource } from "./chat-evidence";
import { EvidenceContext, emptySources } from "./chat-evidence-context";
import { ChatPanelContext as PanelContext } from "@/features/chat/thread/chat-panel-context";
import type { ChatArtifact } from "@/features/chat/thread/chat-artifacts";
import type { PreviewTarget } from "@/features/library/file-preview";

export { ChatMarkdownLink } from "./chat-citation";

// The file viewers are needed only once a file is opened, so they stay out of the conversation's own code.
const ChatFilePreviewModal = lazy(() =>
  import("@/features/library/file-preview-modal").then((module) => ({
    default: module.ChatFilePreviewModal,
  })),
);

export function ChatSourcesWorkspace({ children }: { children: ReactNode }) {
  const panelId = useId();
  const [selection, setSelection] = useState<{
    messageId?: string;
    citationId?: number;
    artifactId?: string;
  }>();
  const [preview, setPreview] = useState<PreviewTarget>();
  const previewTriggerRef = useRef<HTMLElement>(null);
  const returnFocusRef = useRef<HTMLElement>(null);
  const fallbackFocusRef = useRef<HTMLDivElement>(null);
  // Read the selected message from the native runtime so an open panel follows
  // newly committed sources during streaming without copying message state.
  const sources = useAuiState(
    (state) =>
      (state.thread.messages.find((message) => message.id === selection?.messageId)?.metadata.custom
        .sources as ChatSource[] | undefined) ?? emptySources,
  );
  const artifact = useAuiState((state) =>
    (
      state.thread.messages.find((message) => message.id === selection?.messageId)?.metadata.custom
        .artifacts as ChatArtifact[] | undefined
    )?.find((item) => item.id === selection?.artifactId),
  );
  function restoreFocus() {
    const target = returnFocusRef.current?.isConnected
      ? returnFocusRef.current
      : fallbackFocusRef.current;
    target?.focus({ preventScroll: true });
  }
  function close() {
    setSelection(undefined);
    restoreFocus();
  }
  return (
    <PanelContext
      value={{
        panelId,
        messageId: selection?.messageId,
        citationId: selection?.citationId,
        artifactId: selection?.artifactId,
        open: (messageId, trigger, citationId) => {
          returnFocusRef.current = trigger;
          setSelection({ messageId, citationId });
        },
        previewFile: (target, trigger) => {
          previewTriggerRef.current = trigger;
          setPreview(target);
        },
        openArtifact: (messageId, artifactId, trigger) => {
          returnFocusRef.current = trigger;
          setSelection({ messageId, artifactId });
        },
        close,
      }}
    >
      <div
        ref={fallbackFocusRef}
        tabIndex={-1}
        className="flex min-h-0 min-w-0 flex-1 outline-none"
      >
        {children}
        {selection && (artifact || (!selection.artifactId && sources.length > 0)) && (
          <ChatSourcePanel
            id={panelId}
            sources={sources}
            artifact={artifact}
            citationId={selection.citationId}
            onSelect={(citationId) => setSelection({ ...selection, citationId })}
            onClose={close}
            restoreFocus={restoreFocus}
          />
        )}
        {preview && (
          <Suspense fallback={null}>
            <ChatFilePreviewModal
              key={`${preview.source}:${preview.id}`}
              target={preview}
              onClose={() => setPreview(undefined)}
              onCloseAutoFocus={(event) => {
                event.preventDefault();
                const trigger = previewTriggerRef.current;
                (trigger?.isConnected ? trigger : fallbackFocusRef.current)?.focus({
                  preventScroll: true,
                });
              }}
            />
          </Suspense>
        )}
      </div>
    </PanelContext>
  );
}

export function ChatSourcesProvider({ children }: { children: ReactNode }) {
  const messageId = useAuiState((state) => state.message.id);
  const sources = useAuiState(
    (state) => (state.message.metadata.custom.sources as ChatSource[] | undefined) ?? emptySources,
  );
  return <EvidenceContext value={{ messageId, sources }}>{children}</EvidenceContext>;
}

export function ChatSources() {
  const { messageId, sources } = use(EvidenceContext);
  const panel = use(PanelContext);
  const active = panel.messageId === messageId && !panel.artifactId;
  if (!sources.length) return null;
  return (
    <Sources
      count={sources.length}
      sources={sources.map((source) => ({
        url: source.web?.url,
        mediaType: source.mediaType,
        sourceTypes: source.sourceTypes,
      }))}
      aria-expanded={active}
      aria-controls={active ? panel.panelId : undefined}
      onClick={(event) => (active ? panel.close() : panel.open(messageId, event.currentTarget))}
    />
  );
}
