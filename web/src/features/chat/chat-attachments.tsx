import { useAppTranslation } from "@/i18n/use-app-translation";
import {
  ComposerPrimitive,
  useAui,
  useAuiState,
  type FileMessagePartProps,
} from "@assistant-ui/react";
import { Upload } from "lucide-react";
import { Button } from "@/components/ui/button";
import { ChatFilePicker } from "./chat-file-picker";
import { fileReference, fileIdFromReference } from "./chat-files";
import { File as FileDisplay } from "@/components/assistant-ui/elements/file";
import { useChatFilePanel } from "./chat-panel-context";
import { useTranslation } from "react-i18next";

export function ChatComposerFiles() {
  const ui = useAppTranslation();

  const aui = useAui();
  const attachments = useAuiState((state) => state.composer.attachments);
  const identities = attachments.map((attachment) => {
    const part = attachment.content?.find((part) => part.type === "file");
    return part?.type === "file" && typeof part.data === "string"
      ? fileIdFromReference(part.data)
      : undefined;
  });
  return (
    <>
      <ChatFilePicker
        uploadAction={
          <ComposerPrimitive.AddAttachment asChild>
            <Button
              type="button"
              size="sm"
              prominence="internal"
              disabled={attachments.length >= 20}
            >
              <Upload className="size-4" />
              {ui("Tải tệp lên")}
            </Button>
          </ComposerPrimitive.AddAttachment>
        }
        selected={identities.filter((id): id is string => !!id)}
        onSelect={(ids, files) => {
          attachments.forEach((attachment, index) => {
            if (identities[index] && !ids.includes(identities[index]))
              aui.composer.attachment({ id: attachment.id }).remove();
          });
          for (const file of files)
            if (!identities.includes(file.id))
              void aui.composer.addAttachment({
                id: file.id,
                name: file.filename,
                type: "file",
                contentType: file.mediaType,
                content: [
                  {
                    type: "file",
                    filename: file.filename,
                    mimeType: file.mediaType,
                    data: fileReference(file.id),
                    providerMetadata: { memoryos: { sizeBytes: file.sizeBytes } },
                  },
                ],
              });
        }}
      />
    </>
  );
}

export function ChatSharedFilePart({
  filename,
  mimeType,
}: Pick<FileMessagePartProps, "filename" | "mimeType">) {
  const ui = useAppTranslation();

  return (
    <FileDisplay.Root className="my-1 max-w-full">
      <FileDisplay.Icon mimeType={mimeType} />
      <FileDisplay.Name title={filename}>{filename ?? ui("Tệp đính kèm")}</FileDisplay.Name>
    </FileDisplay.Root>
  );
}

export function ChatFilePart(props: Pick<FileMessagePartProps, "data" | "filename" | "mimeType">) {
  const panel = useChatFilePanel();
  const { t } = useTranslation("reader");
  const fileId = typeof props.data === "string" ? fileIdFromReference(props.data) : undefined;
  if (!fileId) return <ChatSharedFilePart {...props} />;
  return (
    <button
      type="button"
      aria-expanded={panel.fileId === fileId}
      aria-controls={panel.fileId === fileId ? panel.panelId : undefined}
      onClick={(event) =>
        panel.openFile(
          { id: fileId, filename: props.filename ?? t("attachment") },
          event.currentTarget,
        )
      }
      className="max-w-full cursor-pointer rounded-lg text-start focus-visible:outline-2"
    >
      <ChatSharedFilePart {...props} />
    </button>
  );
}

export function ChatMessageAttachment({ readOnly }: { readOnly: boolean }) {
  const attachment = useAuiState((state) => state.attachment);
  const part = attachment.content?.find((part) => part.type === "file" || part.type === "image");
  const reference =
    part?.type === "file" ? part.data : part?.type === "image" ? part.image : undefined;
  const props = {
    filename: attachment.name,
    mimeType: attachment.contentType ?? "application/octet-stream",
    data: reference ?? "",
  };
  return readOnly ? <ChatSharedFilePart {...props} /> : <ChatFilePart {...props} />;
}
