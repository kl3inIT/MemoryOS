import { Files } from "lucide-react";
import type { LucideIcon } from "lucide-react";

export type SourceCategory = "Popular";

export type SourceProvider = {
  type: "FILE";
  name: string;
  category: SourceCategory;
  setupPath: "/admin/sources/new/file";
  icon: LucideIcon;
};

export const sourceProviders = [
  {
    type: "FILE",
    name: "Files",
    category: "Popular",
    setupPath: "/admin/sources/new/file",
    icon: Files,
  },
] as const satisfies readonly SourceProvider[];

export const sourceCategories = ["Popular"] as const satisfies readonly SourceCategory[];

export function findSourceProvider(type: string | undefined) {
  return sourceProviders.find((provider) => provider.type === type);
}
