"use client";

import { use, useState } from "react";
import { useAppTranslation } from "@/i18n/use-app-translation";
import { useAui, useAuiState } from "@assistant-ui/react";
import { ImageGeneration } from "@/components/assistant-ui/elements/image-generation";
import { imageArtifactUrl } from "@/features/library/content-urls";
import { type GeneratedImage } from "./chat-image";
import { ChatImageEditDialog } from "./chat-image-edit";
import { ChatImageEditContext } from "./chat-image-edit-context";

/** Renders generated images on an assistant message, plus the in-flight placeholder. */
export function ChatImages() {
  const ui = useAppTranslation();
  const aui = useAui();
  const editing = use(ChatImageEditContext);
  const [target, setTarget] = useState<string>();
  const images = useAuiState(
    (state) => state.message.metadata.custom.images as GeneratedImage[] | undefined,
  );
  const generating = useAuiState(
    (state) => state.message.metadata.custom.imageGenerating as boolean | undefined,
  );
  const resolved = images ?? [];
  if (!generating && resolved.length === 0) return null;
  return (
    <div className="mt-3 flex flex-col gap-3">
      {resolved.map((image) =>
        image.deleted ? (
          <p
            key={image.id}
            data-slot="image-deleted"
            className="rounded-lg border border-border-default px-3 py-2 text-sm text-content-muted"
          >
            {ui("Ảnh đã bị xoá")}
          </p>
        ) : (
          <ImageGeneration
            key={image.id}
            generating={false}
            src={imageArtifactUrl(image.id)}
            prompt={image.revisedPrompt ?? undefined}
            label={image.revisedPrompt ?? ui("Ảnh đã tạo")}
            downloadLabel={ui("Tải ảnh")}
            viewLabel={ui("Xem ảnh phóng to")}
            closeLabel={ui("Đóng")}
            editLabel={ui("Sửa ảnh")}
            onEdit={editing ? () => setTarget(image.id) : undefined}
          />
        ),
      )}
      {generating && <ImageGeneration generating label={ui("Đang tạo ảnh…")} />}
      {editing && target && (
        <ChatImageEditDialog
          imageId={target}
          src={imageArtifactUrl(target)}
          onOpenChange={(open) => {
            if (!open) setTarget(undefined);
          }}
          onSubmit={({ instruction, mask }) => {
            // The thread composer, not this message's edit composer, sends the edit request.
            editing.enableImages();
            const composer = aui.thread.composer();
            composer.setText(instruction);
            void composer.addAttachment(mask);
          }}
        />
      )}
    </div>
  );
}
