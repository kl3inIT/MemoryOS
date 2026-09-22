import { fileArtifactUrl } from "./chat-code";
import { imageArtifactUrl } from "./chat-image";

/**
 * A chat file to preview: a file run_python generated, an attachment, or a generated image, each read
 * through its own owner route. The renderers and the kind classifier are shared with the document viewer
 * in `@/features/preview`; only the owner routes below are Chat's own.
 */
export type PreviewTarget = {
  source: "generated" | "attachment" | "image";
  id: string;
  filename: string;
  /** The stored type when known; a link in an answer only knows the name. */
  mediaType?: string;
};

export function downloadUrl(target: PreviewTarget): string {
  if (target.source === "generated") return fileArtifactUrl(target.id);
  if (target.source === "image") return imageArtifactUrl(target.id);
  return `/api/chat/files/${encodeURIComponent(target.id)}/content`;
}
