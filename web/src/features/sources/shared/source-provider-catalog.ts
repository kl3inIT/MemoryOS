import { Files } from "lucide-react";
import type { ComponentType, SVGProps } from "react";
import { GoogleDriveIcon } from "@/features/sources/google-drive/google-drive-icon";
import { LarkIcon } from "@/features/sources/lark/lark-icon";
import { SharePointIcon } from "@/features/sources/sharepoint/sharepoint-icon";

export type SourceCategory = "Popular";

export type SourceProvider = {
  type: "FILE" | "GOOGLE_DRIVE" | "SHAREPOINT" | "LARK";
  name: string;
  category: SourceCategory;
  setupPath:
    | "/admin/sources/new/file"
    | "/admin/sources/new/google-drive"
    | "/admin/sources/new/sharepoint"
    | "/admin/sources/new/lark";
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
  {
    type: "SHAREPOINT",
    name: "SharePoint",
    category: "Popular",
    setupPath: "/admin/sources/new/sharepoint",
    icon: SharePointIcon,
  },
  {
    type: "LARK",
    name: "Lark",
    category: "Popular",
    setupPath: "/admin/sources/new/lark",
    icon: LarkIcon,
  },
] as const satisfies readonly SourceProvider[];

export const sourceCategories = ["Popular"] as const satisfies readonly SourceCategory[];

export function findSourceProvider(type: string | undefined) {
  return sourceProviders.find((provider) => provider.type === type);
}
