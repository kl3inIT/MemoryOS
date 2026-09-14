import { useAppTranslation } from "@/i18n/use-app-translation";
import { X } from "lucide-react";
import { DocumentSourceIcon } from "./document-source-icon";
import type { DocumentSourceType } from "./document-source-presentation";
import { DocumentMeta } from "./provider-link";
import type { RefObject } from "react";
import { Dialog } from "radix-ui";
import { Button } from "@/components/ui/button";
import { DocumentPreviewContent } from "./document-preview-content";
import { useApplicationSession } from "@/features/identity/application-session-context";
import { readSearchDocumentOriginal } from "@/lib/hey-api/sdk.gen";
import { EvidenceViewSwitch, type PdfEvidence } from "./evidence-view-switch";
import { readSourceLocation } from "./source-provenance";

export type DocumentSelection = {
  documentId: string;
  generation: string;
  title: string;
  mediaType?: string | null;
  sourceTypes?: readonly DocumentSourceType[];
  providerUrl?: string | null;
  /** Provenance of the opened match, used to show its PDF page and region. */
  provenance?: readonly string[];
  matches: Array<{ from: number; matchingOrdinal: number; matchingEndOrdinal?: number }>;
  activeMatchIndex: number;
};

type DocumentPreviewDialogProps = {
  selection: DocumentSelection;
  returnFocusRef: RefObject<HTMLElement | null>;
  fallbackFocusRef: RefObject<HTMLElement | null>;
  onClose: () => void;
};

export function DocumentPreviewDialog({
  selection,
  returnFocusRef,
  fallbackFocusRef,
  onClose,
}: DocumentPreviewDialogProps) {
  const ui = useAppTranslation();
  const { actorId, authorizationVersion } = useApplicationSession();
  const location = readSourceLocation(selection.provenance ?? []);
  const { documentId, generation } = selection;
  const pdf: PdfEvidence | undefined =
    selection.mediaType === "application/pdf" && location.pages.length
      ? {
          queryKey: ["search", actorId, authorizationVersion, documentId, generation],
          load: async (signal) =>
            (
              await readSearchDocumentOriginal({
                path: { documentId },
                query: { generation },
                parseAs: "blob",
                signal,
                throwOnError: true,
              })
            ).data as Blob,
          pages: location.pages,
          boxes: location.boxes,
        }
      : undefined;

  return (
    <Dialog.Root
      open
      onOpenChange={(open) => {
        if (!open) onClose();
      }}
    >
      <Dialog.Portal>
        <Dialog.Overlay className="fixed inset-0 z-40 bg-content-primary/25 backdrop-blur-[2px] data-[state=closed]:animate-out data-[state=open]:animate-in data-[state=closed]:fade-out data-[state=open]:fade-in motion-reduce:animate-none" />
        <Dialog.Content
          className="fixed inset-x-0 bottom-0 z-50 flex max-h-[calc(100dvh-0.5rem)] min-h-[72dvh] flex-col overflow-hidden rounded-t-2xl border border-border-default bg-surface-overlay shadow-md outline-none sm:top-1/2 sm:left-1/2 sm:h-[min(48rem,calc(100dvh-3rem))] sm:min-h-0 sm:w-[min(56rem,calc(100vw-3rem))] sm:-translate-x-1/2 sm:-translate-y-1/2 sm:rounded-2xl"
          onCloseAutoFocus={(event) => {
            const target = returnFocusRef.current?.isConnected
              ? returnFocusRef.current
              : fallbackFocusRef.current;
            if (target?.isConnected) {
              event.preventDefault();
              target.focus();
            }
            returnFocusRef.current = null;
          }}
        >
          <header className="flex shrink-0 items-start justify-between gap-4 border-b border-border-subtle px-5 py-4 sm:px-6">
            <div className="flex min-w-0 items-start gap-3">
              <DocumentSourceIcon
                mediaType={selection.mediaType}
                sourceTypes={selection.sourceTypes}
              />
              <div className="min-w-0">
                <Dialog.Title className="line-clamp-2 break-words font-heading-h3 text-content-primary">
                  {selection.title}
                </Dialog.Title>
                <Dialog.Description className="mt-0.5 font-secondary-body text-content-muted">
                  {selection.mediaType || selection.sourceTypes?.length ? (
                    <DocumentMeta
                      mediaType={selection.mediaType}
                      sourceTypes={selection.sourceTypes}
                      providerUrl={selection.providerUrl}
                      title={selection.title}
                    />
                  ) : (
                    ui("Extracted document text with the selected match highlighted.")
                  )}
                </Dialog.Description>
              </div>
            </div>
            <Dialog.Close asChild>
              <Button prominence="secondary" size="sm" aria-label={ui("Close document preview")}>
                <X className="size-4" aria-hidden="true" />
              </Button>
            </Dialog.Close>
          </header>

          {/* Search opens PDF results on their pages: scanned originals are the reliable evidence. */}
          <EvidenceViewSwitch pdf={pdf} defaultView="pdf">
            <DocumentPreviewContent selection={selection} />
          </EvidenceViewSwitch>
        </Dialog.Content>
      </Dialog.Portal>
    </Dialog.Root>
  );
}
