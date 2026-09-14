"use client";

import { useAppTranslation } from "@/i18n/use-app-translation";
import { useAuiState } from "@assistant-ui/react";
import { ImageGeneration } from "@/components/assistant-ui/elements/image-generation";
import { imageArtifactUrl, type GeneratedImage } from "./chat-image";

/** Renders generated images on an assistant message, plus the in-flight placeholder. */
export function ChatImages() {
  const ui = useAppTranslation();
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
      {resolved.map((image) => (
        <ImageGeneration
          key={image.id}
          generating={false}
          src={imageArtifactUrl(image.id)}
          prompt={image.revisedPrompt ?? undefined}
          label={image.revisedPrompt ?? ui("Ảnh đã tạo")}
          downloadLabel={ui("Tải ảnh")}
        />
      ))}
      {generating && <ImageGeneration generating label={ui("Đang tạo ảnh…")} />}
    </div>
  );
}
