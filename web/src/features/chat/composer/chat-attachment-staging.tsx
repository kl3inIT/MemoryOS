import { useCallback, useMemo, useState, type ReactNode } from "react";
import { useAui } from "@assistant-ui/react";
import { ComposerAttachments } from "@/components/assistant-ui/elements/attachment.aui";
import {
  AttachmentStagingContext,
  useChatAttachmentStaging,
  type AttachmentStaging,
} from "./chat-attachment-staging-context";
import { libraryUpload, type LibraryFile } from "@/features/library/library";
import { composerAttachment } from "./use-composer-file-selection";

type StagedFile = {
  key: string;
  file: LibraryFile;
  failed: boolean;
};

/**
 * Attaching a library file takes a server copy for anything Chat generated (MEM-152). The copy belongs next to
 * the draft's other files rather than behind the picker: the dialog closes on the choice and each file waits as
 * its own composer tile, exactly as an upload does, until it becomes a real attachment.
 */
export function ChatAttachmentStaging({ children }: { children: ReactNode }) {
  const aui = useAui();
  const [staged, setStaged] = useState<StagedFile[]>([]);

  const drop = useCallback(
    (key: string) => setStaged((current) => current.filter((item) => item.key !== key)),
    [],
  );

  const attach = useCallback(
    (files: readonly LibraryFile[]) => {
      const entries = files.map((file) => ({
        key: `${file.source}:${file.id}:${crypto.randomUUID()}`,
        file,
        failed: false,
      }));
      if (entries.length === 0) return;
      setStaged((current) => [...current, ...entries]);
      void (async () => {
        const composer = aui.thread.composer();
        // One at a time: each copy is one server write, and a failure must leave the earlier files attached.
        for (const entry of entries) {
          try {
            const upload = await libraryUpload(entry.file, AbortSignal.timeout(120_000));
            await composer.addAttachment(composerAttachment(upload));
            drop(entry.key);
          } catch {
            setStaged((current) =>
              current.map((item) => (item.key === entry.key ? { ...item, failed: true } : item)),
            );
          }
        }
      })();
    },
    [aui, drop],
  );

  const value = useMemo<AttachmentStaging>(
    () => ({
      pending: staged.map((item) => ({
        id: item.key,
        name: item.file.filename,
        contentType: item.file.mediaType,
        failed: item.failed,
        onRemove: item.failed ? () => drop(item.key) : undefined,
      })),
      preparing: staged.filter((item) => !item.failed).length,
      attach,
    }),
    [staged, attach, drop],
  );

  return (
    <AttachmentStagingContext.Provider value={value}>{children}</AttachmentStagingContext.Provider>
  );
}

/** The draft's files: the attachments assistant-ui holds, plus the library copies still being prepared. */
export function ChatComposerAttachments() {
  const { pending } = useChatAttachmentStaging();
  return <ComposerAttachments pending={pending} />;
}
