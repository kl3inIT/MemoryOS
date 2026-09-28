import { fileArtifactUrl, imageArtifactUrl } from "./content-urls";

/**
 * A library file to preview: a file run_python generated, an upload, or a generated image, each read
 * through its own owner route. The renderers and the kind classifier are shared with the document viewer
 * in `@/features/preview`; only the owner routes below are the library's.
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
