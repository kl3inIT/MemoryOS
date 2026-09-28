/**
 * Where the bytes of a generated file or image are read. They are Chat's artifacts, but the library lists them
 * beside uploads (ADR 0015, step 3), so their serving routes are named here once and Chat's answers read them
 * from here too. The backend authorizes every read.
 */

/** Authorized serving URL for a generated file; the backend enforces ownership. */
export function fileArtifactUrl(id: string): string {
  return `/api/chat/file-artifacts/${id}/content`;
}

/** Which rendering of a generated image to read; the library shows thumbnails, a preview the original. */
export type ImageVariant = "original" | "thumbnail";

/** Authorized serving URL for a generated image; the backend enforces ownership. */
export function imageArtifactUrl(id: string, variant: ImageVariant = "original"): string {
  const url = `/api/chat/image-artifacts/${id}/content`;
  return variant === "thumbnail" ? `${url}?variant=THUMBNAIL` : url;
}
