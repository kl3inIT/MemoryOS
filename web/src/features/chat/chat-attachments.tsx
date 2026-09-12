import { useState } from "react";
import {
  ComposerPrimitive,
  useAui,
  useAuiState,
  type FileMessagePartProps,
} from "@assistant-ui/react";
import { Paperclip } from "lucide-react";
import { Button } from "@/components/ui/button";
import { ChatFilePicker } from "./chat-file-picker";
import { fileReference, fileIdFromReference } from "./chat-files";
import { ComposerAttachments } from "@/components/assistant-ui/elements/attachment.aui";
import { File as FileDisplay } from "@/components/assistant-ui/elements/file";
import { ChatDialog } from "./chat-dialog";
import { ChatFileReader } from "./chat-file-reader";

export function ChatComposerFiles() {
  const aui = useAui();
  const [recent, setRecent] = useState(false);
  const attachments = useAuiState((state) => state.composer.attachments);
  const identities = attachments.map((attachment) => {
    const part = attachment.content?.find((part) => part.type === "file");
    return part?.type === "file" && typeof part.data === "string"
      ? fileIdFromReference(part.data)
      : undefined;
  });
  return (
    <>
      <ComposerAttachments />
      {attachments.length > 20 && (
        <p role="alert" className="text-sm">
          Mỗi tin nhắn có tối đa 20 tệp. Hãy gỡ bớt trước khi gửi.
        </p>
      )}
      <div className="flex gap-2">
        <ComposerPrimitive.AddAttachment asChild>
          <Button type="button" size="sm" prominence="internal" disabled={attachments.length >= 20}>
            <Paperclip className="size-4" />
            Đính kèm
          </Button>
        </ComposerPrimitive.AddAttachment>
        <Button type="button" size="sm" prominence="internal" onClick={() => setRecent(!recent)}>
          Tệp gần đây
        </Button>
      </div>
      {recent && (
        <ChatFilePicker
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
                    },
                  ],
                });
          }}
        />
      )}
    </>
  );
}

export function ChatSharedFilePart({
  filename,
  mimeType,
}: Pick<FileMessagePartProps, "filename" | "mimeType">) {
  return (
    <FileDisplay.Root className="my-1 max-w-full">
      <FileDisplay.Icon mimeType={mimeType} />
      <FileDisplay.Name title={filename}>{filename ?? "Tệp đính kèm"}</FileDisplay.Name>
    </FileDisplay.Root>
  );
}

export function ChatFilePart(props: Pick<FileMessagePartProps, "data" | "filename" | "mimeType">) {
  const [open, setOpen] = useState(false);
  const fileId = typeof props.data === "string" ? fileIdFromReference(props.data) : undefined;
  if (!fileId) return <ChatSharedFilePart {...props} />;
  return (
    <ChatDialog
      title={props.filename ?? "Tệp đính kèm"}
      description="Nội dung tệp riêng tư. Bảng hiển thị giá trị đã trích xuất, không chạy công thức hay mã."
      open={open}
      onOpenChange={setOpen}
      trigger={
        <button
          type="button"
          className="max-w-full cursor-pointer rounded-lg text-start focus-visible:outline-2"
        >
          <ChatSharedFilePart {...props} />
        </button>
      }
    >
      {open && <ChatFileReader key={fileId} fileId={fileId} />}
    </ChatDialog>
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
