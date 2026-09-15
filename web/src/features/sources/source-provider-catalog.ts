import { Files } from "lucide-react";
import type { ComponentType, SVGProps } from "react";
import { GoogleDriveIcon } from "./google-drive-icon";

export type SourceCategory = "Popular";

export type SourceProvider = {
  type: "FILE" | "GOOGLE_DRIVE";
  name: string;
  category: SourceCategory;
  setupPath: "/admin/sources/new/file" | "/admin/sources/new/google-drive";
  icon: ComponentType<SVGProps<SVGSVGElement>>;
};

export const sourceProviders = [
  {
    type: "FILE",
    name: "File",
    category: "Popular",
    setupPath: "/admin/sources/new/file",
    icon: Files,
  },
  {
    type: "GOOGLE_DRIVE",
    name: "Google Drive",
    category: "Popular",
    setupPath: "/admin/sources/new/google-drive",
    icon: GoogleDriveIcon,
  },
] as const satisfies readonly SourceProvider[];

export const sourceCategories = ["Popular"] as const satisfies readonly SourceCategory[];

export function findSourceProvider(type: string | undefined) {
  return sourceProviders.find((provider) => provider.type === type);
}
