import { Files } from "lucide-react";
import type { ComponentType, SVGProps } from "react";
import { GoogleDriveIcon } from "./google-drive-icon";

export type SourceCategory = "Popular";

export type SourceProvider = {
  type: "FILE" | "GOOGLE_DRIVE";
  name: string;
  description: string;
  category: SourceCategory;
  setupPath: "/admin/sources/new/file" | "/admin/sources/new/google-drive";
  icon: ComponentType<SVGProps<SVGSVGElement>>;
};

export const sourceProviders = [
  {
    type: "FILE",
    name: "File",
    description: "Upload PDF, Word, PowerPoint, Excel, CSV, text and Markdown files.",
    category: "Popular",
    setupPath: "/admin/sources/new/file",
    icon: Files,
  },
  {
    type: "GOOGLE_DRIVE",
    name: "Google Drive",
    description: "Index selected Google Drive files and folders and keep them up to date.",
    category: "Popular",
    setupPath: "/admin/sources/new/google-drive",
    icon: GoogleDriveIcon,
  },
] as const satisfies readonly SourceProvider[];

export const sourceCategories = ["Popular"] as const satisfies readonly SourceCategory[];

export function findSourceProvider(type: string | undefined) {
  return sourceProviders.find((provider) => provider.type === type);
}
