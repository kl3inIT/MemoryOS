import type { GoogleDriveRootResponse } from "@/lib/hey-api/types.gen";

export const MAX_GOOGLE_DRIVE_ROOTS = 20;
export const MAX_GOOGLE_DRIVE_LINK_LENGTH = 2048;

export function parseGoogleDriveLinks(value: string) {
  return value
    .split(/[\r\n,]+/)
    .map((link) => link.trim())
    .filter(Boolean);
}

export function googleDriveRootLink(root: GoogleDriveRootResponse) {
  const id = encodeURIComponent(root.id);
  return root.mimeType === "application/vnd.google-apps.folder"
    ? `https://drive.google.com/drive/folders/${id}`
    : `https://drive.google.com/file/d/${id}/view`;
}
